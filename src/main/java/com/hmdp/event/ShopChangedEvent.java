package com.hmdp.event;

import org.springframework.context.ApplicationEvent;

/**
 * 商户变更事件
 * 商户新增或修改后发布，事务提交后由监听器更新 Redis 并同步到 ES。
 * 删除Redis GEO后不再携带previousTypeId：没有“移除旧分类GEO成员”的下游任务。
 * 事件只传主键，监听器按ID重读最新资料；它是应用内事件，不是持久化的RocketMQ消息。
 */
public class ShopChangedEvent extends ApplicationEvent {

    private final Long shopId;
    public ShopChangedEvent(Long shopId) {
        super(shopId);
        this.shopId = shopId;
    }

    public Long getShopId() {
        return shopId;
    }

}
