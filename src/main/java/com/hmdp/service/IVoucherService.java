package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);

    /**
     * 商户为自己店铺发布优惠券（普通券或秒杀券）。
     * shopId 不由调用方传入，由当前商户登录身份确定，防止把券挂到他人店铺（越权）。
     */
    Result addVoucherForOwnShop(Voucher voucher);
}
