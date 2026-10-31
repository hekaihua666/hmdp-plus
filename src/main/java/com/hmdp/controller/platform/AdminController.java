package com.hmdp.controller.platform;

import com.hmdp.dto.AdminLoginDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IAdminService;
import com.hmdp.utils.AdminHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** 平台管理员的登录、登出与身份查询；登录是唯一匿名平台接口。 */
@RestController
@RequestMapping("/platform/admin")
public class AdminController {
    @Resource private IAdminService adminService;
    @PostMapping("/login")
    public Result login(@RequestBody AdminLoginDTO input) { return adminService.login(input); }
    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String token) {
        adminService.logout(token);
        return Result.ok();
    }
    @GetMapping("/me")
    public Result me() { return Result.ok(AdminHolder.get()); }
}
