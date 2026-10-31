package com.hmdp.service;

import com.hmdp.mapper.ShopSyncEventMapper;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商户同步 Outbox 的写入服务：在商户写库的同一个事务里落一条待发送记录，作为后续发 MQ 的持久化锚点。 */
@Service
public class ShopSyncEventService {
    @Resource private ShopSyncEventMapper events;

    /** 记录"这个商户需要同步 ES"。同一商户连续更新时按主键覆盖重置为 PENDING，只留最新一条。 */
    @Transactional
    public void record(Long shopId) {
        events.upsert(shopId);
    }
}
