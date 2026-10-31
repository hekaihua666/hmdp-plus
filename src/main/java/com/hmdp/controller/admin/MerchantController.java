package com.hmdp.controller.admin;

import com.hmdp.dto.MerchantLoginDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IMerchantService;
import com.hmdp.utils.MerchantHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** 商户登录、退出和身份查询；不提供公开注册、角色配置或任意店铺认领。 */
@RestController
@RequestMapping("/admin/merchant")
public class MerchantController {
    @Resource
    private IMerchantService merchantService;

    @PostMapping("/login")
    public Result login(@RequestBody MerchantLoginDTO input) { return merchantService.login(input); }

    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String token) {
        merchantService.logout(token);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me() { return Result.ok(MerchantHolder.get()); }
}
