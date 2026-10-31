package com.hmdp.controller.user;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /** 认证与参数转发放Controller；发布白名单和门店存在性校验集中在Service，避免页面校验被绕过。 */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 点赞 / 取消点赞（Redis ZSet 实现一人一赞）
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 本人入口不接收userId，防止通过修改查询参数读取别人的“我的笔记”列表。
        return blogService.queryBlogByUserId(current, UserHolder.getUser().getId());
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current,
                               @RequestParam(value = "sort", defaultValue = "hot") String sort) {
        if (current < 1 || current > 1000 || !("hot".equals(sort) || "latest".equals(sort))) {
            return Result.fail("笔记页码或排序不合法");
        }
        // hot/latest仍是全站公开笔记，不是“我关注的人”的收件箱；不再依赖feed:{userId}。
        // 根据点赞数降序分页
        Page<Blog> page = blogService.query()
                // 首页可选择最新笔记；不传sort时仍保持原来的热门排序。
                .orderByDesc(!"latest".equals(sort), "liked")
                // 同分时优先近期笔记，再以主键稳定排序，防止翻页时顺序漂移。
                .orderByDesc("create_time", "id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 填充作者信息 + 当前用户是否点赞
        records.forEach(blogService::isBlogLiked);
        return Result.ok(records);
    }

    /**
     * 根据id查询探店博客详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /** 店铺详情的公开关联笔记；只按该店铺查询，分页大小固定，拒绝非法页码。 */
    @GetMapping("/of/shop")
    public Result queryBlogOfShop(@RequestParam("shopId") Long shopId,
                                 @RequestParam(value = "current", defaultValue = "1") Integer current) {
        if (shopId <= 0 || current < 1 || current > 1000) {
            return Result.fail("店铺编号或页码不合法");
        }
        Page<Blog> page = blogService.query().eq("shop_id", shopId)
                .orderByDesc("create_time", "id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 游客没有登录态时，Service 会把 isLike 设为 false，不开放任何写权限。
        page.getRecords().forEach(blogService::isBlogLiked);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /**
     * 最近5位点赞用户
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 根据用户id查询其发布的探店博客（个人主页）
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                    @RequestParam("id") Long id) {
        return blogService.queryBlogByUserId(current, id);
    }

}
