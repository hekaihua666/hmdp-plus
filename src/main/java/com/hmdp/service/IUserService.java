package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    // 签到/sign与/sign/count已从Controller、接口和实现一起删除，避免留下可调用的半截模块。
    // Bitmap/HLL可独立学习，不在本项目中为演示数据结构增加业务接口。

    User createUserWithPhone(String phone);

}
