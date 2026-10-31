package com.hmdp.utils;

import com.hmdp.dto.MerchantDTO;

/** 商户请求上下文，与消费者 UserHolder 隔离；拦截器在请求完成后清理。 */
public final class MerchantHolder {
    private static final ThreadLocal<MerchantDTO> MERCHANT = new ThreadLocal<>();
    private MerchantHolder() { }
    public static void save(MerchantDTO merchant) { MERCHANT.set(merchant); }
    public static MerchantDTO get() { return MERCHANT.get(); }
    public static void remove() { MERCHANT.remove(); }

    /** 服务层再次要求商户身份，避免业务方法被无身份的入口直接调用。 */
    public static Long requireId() {
        if (get() == null) throw new IllegalStateException("未建立商户身份");
        return get().getId();
    }
}
