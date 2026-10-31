package com.hmdp.controller.user;


import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 店铺分类查询接口（C 端公共查询，游客可访问）。
 *
 * <p>作用：给前端提供「店铺有哪些分类」——首页顶部那一排分类图标
 * （美食、酒店、KTV……）的数据就来自这里。</p>
 *
 * <p>缓存思路：分类数据几乎不变，但每次打开首页都要查，所以把
 * <b>整个 List</b> 缓存进 Redis 30 分钟，避免反复查数据库。
 * 这是「缓存整列表」的简单示范，与「缓存单个对象 + 处理缓存穿透/击穿」
 * 的 queryById 形成对比：分类数据少、变化慢，用最简单的「先查缓存、
 * 没查到查库、回填」即可，不需要复杂的穿透/击穿防护。</p>
 *
 * <p>虽然本类位于 user 包，但它是游客可访问的公共只读查询，不要求登录
 * （WebMvcConfig 里已把 /shop-type/** 排除在登录拦截之外）。</p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /** 分类列表的缓存 key，固定一个（所有分类共用一个 key，缓存的是整个 List） */
    private static final String CACHE_KEY = "cache:shop:type:list";

    /**
     * 查询店铺分类列表（如：美食、酒店、KTV……）。
     * 对应 URL：GET /shop-type/list
     *
     * <p>流程：先查 Redis 缓存 → 命中直接返回；未命中查数据库
     * （按 sort 升序）→ 回填缓存（TTL 30 分钟）→ 返回。</p>
     */
    @GetMapping("list")
    public Result queryTypeList() throws JsonProcessingException {
        // ① 先查 Redis 缓存
        String cached = stringRedisTemplate.opsForValue().get(CACHE_KEY);
        // ② 使用应用共享的 Jackson 读取分类，避开 Hutool 转换时的类加载锁竞争。
        if (cached != null) {
            return Result.ok(objectMapper.readValue(cached, new TypeReference<List<ShopType>>() {}));
        }
        // ③ 缓存未命中：查数据库，按 sort 字段升序（保证前端分类图标顺序稳定）
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        // ④ 把整个 List 序列化成 JSON 存进 Redis，并设置 30 分钟过期。
        //    分类变更频率低，设置 TTL 避免缓存长期陈旧（万一以后改了分类，30 分钟后能刷新）。
        stringRedisTemplate.opsForValue().set(CACHE_KEY, JSONUtil.toJsonStr(typeList), 30, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
