package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 平台状态更新白名单：不能夹带密码、店铺归属或会话版本号。 */
@Data
public class MerchantStatusDTO {
    private Integer status;
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("此接口只允许提交status字段");
    }
}
