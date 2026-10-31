package com.hmdp.mq;

import static com.hmdp.constants.SeckillConstants.CANCELLING;
import static com.hmdp.constants.SeckillConstants.INIT;
import static com.hmdp.constants.SeckillConstants.READY;
import static com.hmdp.constants.SeckillConstants.TIMEOUT_REASON;

import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.service.SeckillRequestService;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 三个独立调度任务，分别推进“预占、发送、超时补偿”这三类到点的申请。
 *  拆成三个任务配三个调度线程，是为了让慢吞吞的 MQ 发送不会占住预占和补偿的执行机会。 */
@Component
@Slf4j
public class SeckillRecovery {
    @Resource private SeckillRequestMapper requests;
    @Resource private SeckillRequestService lifecycle;
    @Resource private SeckillOrderPublisher publisher;

    /** 到点就推进一批 INIT 申请去预占。每个调度周期扫一批“下次处理时间已到”的 INIT，逐个调用 prepare。 */
    @Scheduled(initialDelay = 3000, fixedDelayString = "${hmdp.mq.prepare-delay-ms:200}")
    public void preparePending() {
        process(requests.due(INIT), request -> lifecycle.prepare(request.getOrderId()), "预占");
    }

    /** 到点就推进一批 READY 申请去发送。与预占分开调度，发送慢也不拖累预占。 */
    @Scheduled(initialDelay = 3000, fixedDelayString = "${hmdp.mq.publish-delay-ms:200}")
    public void publishPending() {
        process(requests.due(READY), publisher::publish, "发送");
    }

    /** 超时和补偿的收尾任务：超期的 INIT/READY 直接终止，到点的 CANCELLING 去执行补偿释放库存。
     *  超时这里只裁决数据库状态，Redis 的释放在 CANCELLING 分支里由 compensate 完成。 */
    @Scheduled(initialDelay = 3000, fixedDelay = 1000)
    public void recover() {
        process(requests.expired(INIT), request -> lifecycle.cancel(request.getOrderId(), TIMEOUT_REASON), "超时裁决");
        process(requests.expired(READY), request -> lifecycle.cancel(request.getOrderId(), TIMEOUT_REASON), "超时裁决");
        process(requests.due(CANCELLING),
                request -> lifecycle.compensate(request.getOrderId()), "补偿");
    }

    /** 逐条执行一批操作，单条失败就退避重试，绝不因一条异常跳过整批剩余申请。
     *  每批最多 20 条（由 mapper 的 LIMIT 控制），这里只管一条条隔离处理。 */
    private void process(List<SeckillRequest> batch, Consumer<SeckillRequest> operation, String stage) {
        for (SeckillRequest request : batch) {
            try {
                operation.accept(request);
            } catch (Exception error) {
                log.warn("抢购恢复未完成，stage={}，orderId={}", stage, request.getOrderId(), error);
                try {
                    requests.defer(request.getOrderId());
                } catch (Exception deferError) {
                    log.error("抢购退避记录失败，orderId={}", request.getOrderId(), deferError);
                }
            }
        }
    }
}
