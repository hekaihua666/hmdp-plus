package com.hmdp.controller.platform;

import com.hmdp.dto.MerchantStatusDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IAdminService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** 平台可以管理全体商户账号；店铺编辑仍走各商户自己的B端接口。 */
@RestController
@RequestMapping("/platform/merchant")
public class PlatformMerchantController {
    @Resource private IAdminService adminService;
    @GetMapping
    public Result list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       @RequestParam(defaultValue = "1") int current,
                       @RequestParam(defaultValue = "20") int pageSize) {
        return adminService.listMerchants(keyword, status, current, pageSize);
    }
    @PutMapping("/{id}/status")
    public Result changeStatus(@PathVariable Long id, @RequestBody MerchantStatusDTO input) {
        return adminService.changeMerchantStatus(id, input.getStatus());
    }
}
