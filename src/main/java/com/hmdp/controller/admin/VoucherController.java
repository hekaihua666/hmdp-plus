package com.hmdp.controller.admin;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 商户端优惠券管理：发布普通券 / 秒杀券。
 *
 * <p>店铺归属由登录身份在服务层确定，不接受前端传入 shopId，防止把券挂到他人店铺（越权）。
 * 接口统一挂在 /admin/** 下，由 MerchantLoginInterceptor 校验商户登录态。</p>
 */
@RestController("merchantVoucherController")
@RequestMapping("/admin/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /** 发布优惠券（type=0 普通券；type=1 秒杀券）。 */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {

        return voucherService.addVoucherForOwnShop(voucher);
    }
}
