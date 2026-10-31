package com.hmdp.dto;

import com.hmdp.entity.Merchant;
import lombok.Data;

/** 后台显示和请求上下文使用的账号信息，不含密码。 */
@Data
public class MerchantDTO {
    private Long id;
    private String username;
    private String name;

    public static MerchantDTO from(Merchant merchant) {
        MerchantDTO dto = new MerchantDTO();
        dto.setId(merchant.getId());
        dto.setUsername(merchant.getUsername());
        dto.setName(merchant.getName());
        return dto;
    }
}
