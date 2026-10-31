package com.hmdp.utils;

import com.hmdp.dto.AdminDTO;

/** 独立管理员请求上下文；拦截器在请求结束后清理，避免线程复用串号。 */
public final class AdminHolder {
    private static final ThreadLocal<AdminDTO> ADMIN = new ThreadLocal<>();
    private AdminHolder() { }
    public static void save(AdminDTO admin) { ADMIN.set(admin); }
    public static AdminDTO get() { return ADMIN.get(); }
    public static void remove() { ADMIN.remove(); }
    /** 平台业务服务也要求管理员身份，不能从其他无鉴权入口绕过。 */
    public static Long requireId() {
        if (get() == null) throw new IllegalStateException("未建立平台管理员身份");
        return get().getId();
    }
}
