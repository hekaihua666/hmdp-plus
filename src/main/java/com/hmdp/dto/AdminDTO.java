package com.hmdp.dto;

import com.hmdp.entity.Admin;
import lombok.Data;

/** 平台请求上下文与身份展示只需要id、username，不携带密码或状态编辑能力。 */
@Data
public class AdminDTO {
    private Long id;
    private String username;
    public static AdminDTO from(Admin admin) {
        AdminDTO dto = new AdminDTO();
        dto.setId(admin.getId());
        dto.setUsername(admin.getUsername());
        return dto;
    }
}
