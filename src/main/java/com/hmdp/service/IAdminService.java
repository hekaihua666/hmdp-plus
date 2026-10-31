package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.AdminDTO;
import com.hmdp.dto.AdminLoginDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Admin;

/** 最小平台能力：管理员会话、商户分页查询与状态变更。 */
public interface IAdminService extends IService<Admin> {
    Result login(AdminLoginDTO input);
    AdminDTO authenticate(String token);
    void logout(String token);
    Result listMerchants(String keyword, Integer status, int current, int pageSize);
    Result changeMerchantStatus(Long id, Integer status);
}
