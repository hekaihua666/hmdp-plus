package com.hmdp.controller.user;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 前端控制器
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 返回受理、成功或失败状态；不能将“接口返回订单ID”直接当成落库成功。 */
    @GetMapping("/requests/{id}")
    public Result queryRequest(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryRequest(orderId);
    }

    @GetMapping("/requests")
    public Result queryRequests(@RequestParam Long shopId) {
        return voucherOrderService.queryRequests(shopId);
    }
}
