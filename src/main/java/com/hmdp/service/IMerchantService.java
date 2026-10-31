package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.MerchantDTO;
import com.hmdp.dto.MerchantLoginDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Merchant;

/** 商户账号和可撤销的 Redis 登录态。 */
public interface IMerchantService extends IService<Merchant> {
    Result login(MerchantLoginDTO input);
    MerchantDTO authenticate(String token);
    void logout(String token);
}
