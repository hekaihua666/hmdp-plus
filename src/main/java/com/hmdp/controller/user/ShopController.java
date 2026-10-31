package com.hmdp.controller.user;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.constants.ShopSearchRanges;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.ShopSearchResult;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopDoc;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 店铺相关接口的 HTTP 入口（C 端）。
 *
 * <p>本类只负责「接收 HTTP 请求 → 转发给 Service → 包装返回」，不写具体业务逻辑。</p>
 *
 * <p>注意：本类虽位于 user 包，但其中的查询接口（详情/分类/名称/搜索/补全/选项）都是
 * 「游客可访问」的公共只读查询，不要求登录——与 user 包内其它需登录的个人业务不同。</p>
 *
 * <p>接口分两类：</p>
 * <ul>
 *   <li>消费者业务：详情缓存、发布笔记时按名称选择门店</li>
 *   <li>ES 搜索：/search、/search/options、/suggest（走 Elasticsearch）</li>
 * </ul>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    // 原GET /shop/of/type已退役。分类页和附近页都调用/search，统一过滤、分页和聚合语义。
    // /of/name仍用于发布笔记时选择门店，保留它不代表保留第二套附近检索。

    /** 店铺业务 Service：查询、新增、更新、分类查询等（含详情缓存逻辑） */
    @Resource
    public IShopService shopService;

    /** 店铺搜索 Service：ES 搜索、补全、同步索引等 */
    @Resource
    private IShopSearchService shopSearchService;

    /**
     * 根据 id 查询单个店铺详情。
     * 对应 URL：GET /shop/{id}，例如 GET /shop/1
     *
     * <p>真正的「查缓存 → 查数据库 → 回填缓存」逻辑在 shopService.queryById 里，
     * 这里只负责转发。</p>
     *
     * @param id 路径参数，店铺 id
     * @return 店铺详情；不存在则返回失败
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {

        return shopService.queryById(id);
    }

    /**
     * 按名称关键字查询店铺（MySQL LIKE 模糊查询）。
     * 对应 URL：GET /shop/of/name?name=海底捞&current=1
     *
     * <p>服务对象是「发布笔记时选关联商户」的 blog-edit.html 页面，不是商户搜索页。
     * 商户搜索页用的是下面的 /shop/search（ES）。此接口有实际调用方，不能删除。</p>
     *
     *用户发一篇点评笔记时，需要关联一家店铺
     * @param name    店铺名称关键字（可选，为空则查全部）
     * @param current 页码，默认第 1 页
     * @return 店铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 按名称模糊分页查询，不参与 ES 商户组合搜索。
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }

    /**
     * ES 组合搜索商户。
     * 对应 URL：GET /shop/search?keyword=火锅&minPrice=50&maxPrice=100&...
     *
     * <p>所有搜索条件都放在 ShopSearchDTO 里，支持：关键词（中文/拼音/首字母）、
     * 分类、价格区间、评分区间、距离、排序、分页、高亮、聚合。真正的 ES 查询逻辑
     * 在 shopSearchService.search 里。</p>
     *
     * <p>这里额外做了两件事：</p>
     * <ul>
     *   <li>把每张店铺图片列表只保留第一张（列表页只要封面图）</li>
     *   <li>捕获异常，包装成友好的失败提示，避免把 ES 底层异常堆栈抛给前端</li>
     * </ul>
     *
     * @param dto 搜索条件（Spring 自动把 URL 查询参数绑定进来）
     * @return 搜索结果（商户列表 + 总数 + 筛选聚合）
     */
    @GetMapping("/search")
    public Result searchShops(ShopSearchDTO dto) {
        try {
            ShopSearchResult result = shopSearchService.search(dto);
            // 图片取第一张：images 是逗号拼接的多张图（如 "a.jpg,b.jpg"），列表页只需封面图
            if (result.getList() != null) {
                for (ShopDoc doc : result.getList()) {
                    if (StrUtil.isNotBlank(doc.getImages())) {
                        doc.setImages(doc.getImages().split(",")[0]);
                    }
                }
            }
            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("搜索服务异常：" + e.getMessage());
        }
    }

    /**
     * 返回「价格区间、评分区间」的筛选选项，给前端渲染筛选按钮。
     * 对应 URL：GET /shop/search/options
     *
     * <p>这份区间配置是「单一数据源」：前端按钮用它渲染，后端 /search 做 ES 聚合
     * 也用它，保证两边区间永远一致。且纯静态返回，不依赖 ES 在线，页面一加载就能拿到。</p>
     *
     * @return 价格、评分两组区间选项
     */
    @GetMapping("/search/options")
    public Result searchOptions() {
        return Result.ok(ShopSearchRanges.options());
    }

    /**
     * 搜索输入自动补全（边打字边提示）。
     * 对应 URL：GET /shop/suggest?keyword=hdl
     *
     * <p>用户在搜索框输入时，每敲一个字符就调一次。用 ES 的 completion suggester
     * 在预生成的「中文/全拼/首字母」建议词上做前缀匹配，命中后返回中文商户名。
     * 例如输入 hdl 能提示「海底捞火锅」。</p>
     *
     * @param keyword 用户当前输入的内容（可能是中文、拼音或首字母）
     * @return 匹配的商户名称列表
     */
    @GetMapping("/suggest")
    public Result suggestShops(@RequestParam String keyword) {
        try {
            return Result.ok(shopSearchService.suggest(keyword));
        } catch (Exception e) {
            return Result.fail("补全失败：" + e.getMessage());
        }
    }
}
