package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 商户全表单更新白名单。店铺 ID 由登录身份确定，归属、销量和评分不可提交。
 * 拒绝未知字段，防止调用者误以为提交的受保护字段已经被修改。
 */
@Data
public class ShopUpdateDTO {
    private String name;
    private Long typeId;
    private String images;
    private String area;
    private String address;
    private Double x;
    private Double y;
    private Long avgPrice;
    private String openHours;

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("店铺编辑不接受字段：" + field);
    }
}
