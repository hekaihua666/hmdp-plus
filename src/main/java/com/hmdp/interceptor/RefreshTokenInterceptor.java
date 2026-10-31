package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * token 刷新/身份识别拦截器（第一个拦截器，负责「识别身份 + 刷新有效期」）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>从请求头拿 token；没有 token 直接放行（匿名访问）</li>
 *   <li>有 token → 查 Redis（Hash 存的是脱敏后的用户信息）</li>
 *   <li>查到了 → 把用户信息放进 ThreadLocal，并刷新 token 有效期（滑动过期）</li>
 *   <li>请求结束后清理 ThreadLocal</li>
 * </ol>
 *
 * <p>它不拦截任何请求（都放行），只是「识别身份」。真正强制登录由 LoginInterceptor 做。</p>
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(token == null){
            //没有token直接放行（匿名访问，交给 LoginInterceptor 决定是否拦截）
            return true;
        }
        //2.从redis获取用户hash（登录时存的是 Hash：字段=用户属性，值=属性值）
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if(userMap.isEmpty()){
            return true;   // token 无效/过期，放行（当作未登录）
        }
        //4.把map转为UserDTO（脱敏用户对象）
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5.保存到ThreadLocal（业务代码可通过 UserHolder.getUser() 拿到）
        UserHolder.saveUser(userDTO);
        //6.刷新token有效期：每次有请求就续期（滑动过期，活跃用户不过期）
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //请求结束移除，防止内存泄漏和线程复用串号
        UserHolder.removeUser();
    }
}
