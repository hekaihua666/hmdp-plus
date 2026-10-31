package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器（第二个拦截器，负责「强制登录」）。
 *
 * <p>和 RefreshTokenInterceptor 分工：</p>
 * <ul>
 *   <li>RefreshTokenInterceptor：负责「识别身份」——有 token 就把用户放进 ThreadLocal，没 token 也放行</li>
 *   <li>LoginInterceptor（本类）：负责「强制登录」——ThreadLocal 里没有用户就 401 拦截</li>
 * </ul>
 *
 * <p>两者配合实现「有的接口必须登录、有的接口登录了能识别身份但也能匿名访问」。</p>
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断 ThreadLocal 里有没有用户（RefreshTokenInterceptor 是否已识别出身份）
        if(UserHolder.getUser() == null){
            // 没有 → 401 拦截，不放行
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
