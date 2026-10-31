package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

/** 独立商户账号：消费者的短信账号不具有后台管理身份。 */
@Data
@TableName("tb_merchant")
public class Merchant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 自带随机盐；任何响应都不返回密码哈希。 */
    @JsonIgnore
    private String passwordHash;
    private String name;
    /** 1 为启用，0 为停用；每次后台请求重新确认状态。 */
    private Integer status;
    /** 封禁时递增；token版本不匹配即失效，解封不让旧登录态复活。 */
    @JsonIgnore
    private Long sessionVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
