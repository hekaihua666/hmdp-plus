package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.MerchantLoginInterceptor;
import com.hmdp.interceptor.AdminLoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Resource
    private MerchantLoginInterceptor merchantLoginInterceptor;

    @Resource
    private AdminLoginInterceptor adminLoginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //order越小越优先，全局拦截所有请求，刷新token
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/**", "/platform/**")
                .order(0);

        // 公开阅读采用明确的路径白名单，不直接排除/blog/**或/user/**。
        // 否则作者主页虽能读取，发布、点赞、本人列表等写操作/私有数据也可能被一起放开。
        // /upload/**已不在白名单：照片属于发布流程，必须验证消费者登录身份。
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/admin/**",
                        "/platform/**",
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/blog/of/shop",
                        // 作者笔记和公开资料供游客阅读；资料返回字段白名单在UserController中控制。
                        "/blog/of/user",
                        "/user/{id:[0-9]+}",
                        "/user/info/{id:[0-9]+}",
                        // 限定数字ID，避免/user/me、/blog/of/me等受保护路径被通配进去。
                        // 笔记正文及点赞名单属于公开阅读，发布、点赞、关注仍需登录。
                        "/blog/{id:[0-9]+}",
                        "/blog/likes/{id:[0-9]+}",
                        "/user/code",
                        "/user/login"
                ).order(1);
        // B 端单独验证商户账号，不能用消费者身份替代；登录是唯一匿名后台入口。
        registry.addInterceptor(merchantLoginInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/merchant/login").order(1);
        // 平台登录态独立验证；商户token不能进入更高层的平台后台。
        registry.addInterceptor(adminLoginInterceptor).addPathPatterns("/platform/**")
                .excludePathPatterns("/platform/admin/login").order(1);
    }
}
