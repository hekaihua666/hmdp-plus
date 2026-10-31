package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hmdp.config.SeckillMqConfiguration;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mq.SeckillOrderPublisher;
import com.hmdp.mq.SeckillRecovery;
import com.hmdp.service.SeckillRequestService;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 使用实际 Spring 调度配置，验证发送被阻塞时补偿任务仍有线程执行。 */
class SeckillSchedulingTest {
    @Test
    void blockedPublisherDoesNotHoldUpCompensation() throws Exception {
        SeckillRequestMapper requests = mock(SeckillRequestMapper.class);
        SeckillRequestService lifecycle = mock(SeckillRequestService.class);
        SeckillOrderPublisher publisher = mock(SeckillOrderPublisher.class);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch compensated = new CountDownLatch(1);
        SeckillRequest request = new SeckillRequest();
        request.setOrderId(1L);
        when(requests.due("READY")).thenReturn(Collections.singletonList(request));
        when(requests.due("CANCELLING")).thenReturn(Collections.singletonList(request));
        doAnswer(call -> { sending.countDown(); release.await(10, TimeUnit.SECONDS); return null; })
                .when(publisher).publish(any());
        doAnswer(call -> { compensated.countDown(); return null; }).when(lifecycle).compensate(1L);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "review", Collections.singletonMap("spring.task.scheduling.pool.size", 3)));
            // 用 registerResolvableDependency 而不是 registerBean：mock 是目标类的子类、继承了它的 @Resource 字段，
            // 若当普通 bean 注册，Spring 会对这些继承字段做注入、因缺 VoucherOrderMapper 等依赖而失败。
            context.getBeanFactory().registerResolvableDependency(SeckillRequestMapper.class, requests);
            context.getBeanFactory().registerResolvableDependency(SeckillRequestService.class, lifecycle);
            context.getBeanFactory().registerResolvableDependency(SeckillOrderPublisher.class, publisher);
            context.register(SeckillMqConfiguration.class, TaskSchedulingAutoConfiguration.class, SeckillRecovery.class);
            context.refresh();
            try {
                assertTrue(sending.await(6, TimeUnit.SECONDS), "后台发送应启动");
                assertTrue(compensated.await(2, TimeUnit.SECONDS), "发送阻塞时补偿仍应执行");
            } finally {
                release.countDown();
            }
        }
    }
}
