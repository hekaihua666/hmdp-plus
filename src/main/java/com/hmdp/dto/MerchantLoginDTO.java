package com.hmdp.dto;

import lombok.Getter;
import lombok.Setter;

/** 登录输入不生成 toString，避免日志意外输出密码。 */
@Getter
@Setter
public class MerchantLoginDTO {
    private String username;
    private String password;
}
