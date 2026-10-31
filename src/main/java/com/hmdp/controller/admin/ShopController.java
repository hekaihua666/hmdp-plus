package com.hmdp.controller.admin;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.service.IShopService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** 桌面后台的“我的店铺”：目标店铺由服务端身份确定，不接受店铺 ID 或归属字段。 */
@RestController("merchantShopController")
@RequestMapping("/admin/shop")
public class ShopController {
    @Resource
    private IShopService shopService;

    @GetMapping
    public Result mine() { return shopService.queryOwnShop(); }

    @PutMapping
    public Result update(@RequestBody ShopUpdateDTO input) { return shopService.updateOwnShop(input); }
}
