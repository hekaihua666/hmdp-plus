package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    // 轻社交保留门店笔记的读写与点赞；不再定义queryBlogOfFollow及ScrollResult协议。

    /**
     * 根据id查询探店博客详情（含作者信息、当前用户是否点赞）
     */
    Result queryBlogById(Long id);

    /**
     * 点赞 / 取消点赞（同一个用户只能点一次，再次点击取消）
     */
    Result likeBlog(Long id);

    /**
     * 查询最近5位点赞用户，按点赞时间倒序；不是用户热度排行榜。
     */
    Result queryBlogLikes(Long id);

    /**
     * 根据用户id分页查询其发布的探店博客
     */
    Result queryBlogByUserId(Integer current, Long userId);

    /**
     * 给博客填充作者昵称、头像、当前用户是否点赞
     */
    void isBlogLiked(Blog blog);

    /** 发布与门店关联的笔记，不向粉丝写扩散。 */
    Result saveBlog(Blog blog);
}
