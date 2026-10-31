package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注 / 取关
     * @param followUserId 要关注的用户id
     * @param isFollow true=关注 false=取关
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前登录用户是否关注了目标用户
     */
    Result isFollow(Long followUserId);

    /** 分页查看本人关注的作者，不构建动态收件箱。 */
    Result queryFollowing(Integer current);
}
