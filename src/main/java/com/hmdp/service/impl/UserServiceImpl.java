package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import org.springframework.stereotype.Service;

/** 消费者账号服务；仅保留登录时自动创建用户的职责。 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    /** 手机号不存在时创建账号；短信校验与会话签发仍由原登录入口处理。 */
    @Override
    public User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + phone.substring(6));
        save(user);
        return user;
    }
}
