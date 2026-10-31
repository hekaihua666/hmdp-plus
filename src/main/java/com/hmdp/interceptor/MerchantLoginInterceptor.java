package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.MerchantDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IMerchantService;
import com.hmdp.utils.MerchantHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** 所有 /admin/** 管理请求只接受商户登录态，消费者 token 无法建立商户身份。 */
@Component
public class MerchantLoginInterceptor implements HandlerInterceptor {
    @Resource
    private IMerchantService merchantService;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        MerchantHolder.remove();
        MerchantDTO merchant = merchantService.authenticate(request.getHeader("authorization"));
        if (merchant == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), Result.fail("商户登录已失效，请重新登录"));
            return false;
        }
        MerchantHolder.save(merchant);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MerchantHolder.remove();
    }
}
