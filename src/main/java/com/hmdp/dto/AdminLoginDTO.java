package com.hmdp.dto;

import lombok.Data;

/** 平台登录仅接收账号与密码，没有公开注册或角色选择字段。 */
@Data
public class AdminLoginDTO {
    private String username;
    private String password;
}
