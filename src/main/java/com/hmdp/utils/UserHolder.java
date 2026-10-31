package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 当前登录用户上下文（基于 ThreadLocal）。
 *
 * <p>ThreadLocal = 每个线程一个独立的「小盒子」。拦截器在请求进来时把当前用户
 * 放进盒子里，业务代码（Service）直接 getUser() 就能拿到「当前是谁」，不用层层传参；
 * 请求结束后 removeUser() 清空，避免线程复用（Tomcat 线程池）时串号导致拿到上一个人的身份。</p>
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /** 保存当前登录用户（拦截器在请求处理前调用） */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /** 获取当前登录用户（业务代码任意位置调用，可能为 null 表示未登录） */
    public static UserDTO getUser(){
        return tl.get();
    }

    /** 清空（拦截器在请求结束后调用，防止内存泄漏和线程复用串号） */
    public static void removeUser(){
        tl.remove();
    }
}
