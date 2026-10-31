package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.AdminDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IAdminService;
import com.hmdp.utils.AdminHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** /platform/**只接受管理员命名空间的token，不复用消费者或商户身份。 */
@Component
public class AdminLoginInterceptor implements HandlerInterceptor {
    @Resource private IAdminService adminService;
    @Resource private ObjectMapper objectMapper;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AdminHolder.remove();
        AdminDTO admin = adminService.authenticate(request.getHeader("authorization"));
        if (admin == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), Result.fail("平台登录已失效，请重新登录"));
            return false;
        }
        AdminHolder.save(admin);
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminHolder.remove();
    }
}
