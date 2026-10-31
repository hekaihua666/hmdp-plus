package com.hmdp.mq;

import com.hmdp.entity.ShopSyncEvent;
import com.hmdp.mapper.ShopSyncEventMapper;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 商户同步的恢复任务：定时扫到点的 PENDING 记录去发 MQ，单条失败退避重试，不拖累同批其他记录。 */
@Component
@Slf4j
public class ShopSyncRecovery {
    @Resource private ShopSyncEventMapper events;
    @Resource private ShopSyncPublisher publisher;

    /** 每个周期扫一批到点的待发送记录，逐个交给 publisher 发送。 */
    @Scheduled(initialDelay = 3000, fixedDelayString = "${hmdp.mq.shop-sync-delay-ms:200}")
    public void publishPending() {
        process(events.due(), publisher::publish);
    }

    /** 逐条执行，单条异常退避 10 秒后继续下一条，不让一条失败拖垮整批。 */
    private void process(List<ShopSyncEvent> batch, Consumer<ShopSyncEvent> operation) {
        for (ShopSyncEvent event : batch) {
            try {
                operation.accept(event);
            } catch (Exception error) {
                log.warn("商户同步恢复未完成，shopId={}", event.getShopId(), error);
                try {
                    events.sendFailed(event.getShopId(), event.getRevision(), "恢复任务执行异常");
                } catch (Exception deferError) {
                    log.error("商户同步退避记录失败，shopId={}", event.getShopId(), deferError);
                }
            }
        }
    }
}
