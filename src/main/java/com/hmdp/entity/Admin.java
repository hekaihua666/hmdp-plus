package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

/** 平台管理员账号。与tb_merchant、tb_user分表，不通过客户端传入的role决定权限。 */
@Data
@TableName("tb_admin")
public class Admin {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** 哈希既不序列化到响应，也不出现在对象日志中。 */
    @JsonIgnore
    @ToString.Exclude
    private String passwordHash;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
