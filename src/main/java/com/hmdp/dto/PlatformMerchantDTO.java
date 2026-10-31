package com.hmdp.dto;

import com.hmdp.entity.Merchant;
import lombok.Data;
import java.time.LocalDateTime;

/** 商户列表视图：显式列出允许展示的字段，不返回密码哈希和会话版本。 */
@Data
public class PlatformMerchantDTO {
    private Long id;
    private String username;
    private String name;
    private Integer status;
    private LocalDateTime createTime;
    private Long shopId;
    private String shopName;
    /** 门店首图与分类用于运营辨认；不返回店铺归属字段或账号认证信息。 */
    private String shopCover;
    private String shopTypeName;
    private String shopArea;
    private String shopAddress;
    public static PlatformMerchantDTO from(Merchant merchant) {
        PlatformMerchantDTO dto = new PlatformMerchantDTO();
        dto.setId(merchant.getId());
        dto.setUsername(merchant.getUsername());
        dto.setName(merchant.getName());
        dto.setStatus(merchant.getStatus());
        dto.setCreateTime(merchant.getCreateTime());
        return dto;
    }
}
