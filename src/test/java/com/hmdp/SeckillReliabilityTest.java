package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.SeckillOrderPublisher;
import com.hmdp.mq.SeckillRecovery;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.SeckillRequestService;
import com.hmdp.service.SeckillReservationService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 回归审查发现的故障窗口、受理成本与恢复隔离；实际存储事务由显式集成测试覆盖。 */
class SeckillReliabilityTest {
    private SeckillRequestMapper requests;
    private SeckillReservationService reservation;
    private ISeckillVoucherService vouchers;
    private PlatformTransactionManager transactions;
    private SeckillRequestService lifecycle;
    private SeckillRequest request;
    private SeckillVoucher voucher;

    @BeforeEach
    void setup() {
        requests = mock(SeckillRequestMapper.class);
        reservation = mock(SeckillReservationService.class);
        vouchers = mock(ISeckillVoucherService.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        lifecycle = new SeckillRequestService();
        ReflectionTestUtils.setField(lifecycle, "requests", requests);
        ReflectionTestUtils.setField(lifecycle, "reservation", reservation);
        ReflectionTestUtils.setField(lifecycle, "vouchers", vouchers);
        ReflectionTestUtils.setField(lifecycle, "transactionManager", transactions);
        request = new SeckillRequest();
        request.setOrderId(1L);
        request.setUserId(7L);
        request.setVoucherId(8L);
        request.setState("INIT");
        request.setReservationState("NONE");
        request.setDeadline(LocalDateTime.now().plusMinutes(10));
        when(requests.lock(1L)).thenReturn(request);
        voucher = new SeckillVoucher();
        voucher.setVoucherId(8L);
        voucher.setBeginTime(LocalDateTime.now().minusHours(1));
        voucher.setEndTime(LocalDateTime.now().plusHours(1));
        when(vouchers.getById(8L)).thenReturn(voucher);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void unknownIntentCommitsBeforeRedisAndSurvivesSecondTransactionFailure() {
        when(reservation.reserve(request, voucher, true)).thenThrow(new IllegalStateException("确认丢失"));
        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(1L));
        assertEquals("INIT", request.getState());
        assertEquals("UNKNOWN", request.getReservationState());
        // 关键约束是提交顺序：记录未知意图的事务先提交，随后 Redis 异常只回滚第二段。
        InOrder order = inOrder(requests, transactions, reservation);
        order.verify(requests).lock(1L);
        order.verify(requests).updateById(request);
        order.verify(transactions).commit(any());
        order.verify(requests).lock(1L);
        order.verify(reservation).reserve(request, voucher, true);
        order.verify(transactions).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4})
    void rejectionWritesOneIntentAndOneDecisionWithoutDuplicateCancellingUpdate(long code) {
        List<String> states = new ArrayList<>();
        when(requests.updateById(any(SeckillRequest.class))).thenAnswer(call -> {
            states.add(((SeckillRequest) call.getArgument(0)).getState());
            return 1;
        });
        when(reservation.reserve(request, voucher, true)).thenReturn(code);
        lifecycle.prepare(1L);
        assertEquals(Arrays.asList("INIT", "CANCELLING"), states);
        assertEquals("NONE", request.getReservationState());
    }

    @ParameterizedTest
    @ValueSource(longs = {5, 6})
    void missingStockOrRetryEvidenceNeverTurnsUnknownIntoNoReservation(long code) {
        request.setReservationState("UNKNOWN");
        when(reservation.reserve(request, voucher, false)).thenReturn(code);
        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(1L));
        assertEquals("UNKNOWN", request.getReservationState());
        assertEquals("INIT", request.getState());
        verify(requests, never()).updateById(any());
    }

    @Test
    void confirmedReservationRetryDoesNotRequireANewReservation() {
        request.setReservationState("UNKNOWN");
        when(reservation.reserve(request, voucher, false)).thenReturn(0L);
        lifecycle.prepare(1L);
        assertEquals("READY", request.getState());
        assertEquals("HELD", request.getReservationState());
        verify(requests).updateById(request);
    }

    @Test
    void expiredUnattemptedRequestCanTerminateWithoutBecomingUnknown() {
        request.setDeadline(LocalDateTime.now().minusMinutes(1));
        lifecycle.prepare(1L);
        assertEquals("CANCELLING", request.getState());
        assertEquals("NONE", request.getReservationState());
        verifyNoInteractions(reservation);
        verify(requests).updateById(request);
    }

    @Test
    void knownMissingStockCanRetryFreshAfterRepair() {
        when(reservation.reserve(request, voucher, true)).thenReturn(5L);
        lifecycle.prepare(1L);
        assertEquals("INIT", request.getState());
        assertEquals("NONE", request.getReservationState());
        assertTrue(request.getNextSendTime().isAfter(LocalDateTime.now()));
    }

    @Test
    void activeReservationLeaseDoesNotRaceTheFirstCaller() {
        request.setReservationState("UNKNOWN");
        request.setNextSendTime(LocalDateTime.now().plusSeconds(10));
        lifecycle.prepare(1L);
        verifyNoInteractions(reservation);
        verify(requests, never()).updateById(any());
    }

    @Test
    void missingCompensationEvidenceKeepsRequestRecoverable() {
        request.setState("CANCELLING");
        request.setReservationState("HELD");
        doThrow(new IllegalStateException("凭据待核查")).when(reservation).release(request);
        assertThrows(IllegalStateException.class, () -> lifecycle.compensate(1L));
        assertEquals("CANCELLING", request.getState());
        verify(requests, never()).updateById(any());
    }

    @Test
    void failedStateIsWrittenOnlyAfterReleaseSucceeds() {
        request.setState("CANCELLING");
        request.setReservationState("HELD");
        lifecycle.compensate(1L);
        InOrder order = inOrder(reservation, requests);
        order.verify(requests).lock(1L);
        order.verify(reservation).release(request);
        order.verify(requests).updateById(request);
        assertEquals("FAILED", request.getState());
    }

    @Test
    void repeatedCancellationPreservesOriginalReasonAndBackoff() {
        request.setState("CANCELLING");
        request.setLastError("原始终止原因");
        lifecycle.cancel(1L, "迟到的重复终止");
        assertEquals("原始终止原因", request.getLastError());
        verify(requests, never()).updateById(any());
    }

    @Test
    void acceptedHttpRequestOnlyReadsVoucherAndInsertsApplication() {
        VoucherOrderServiceImpl entry = new VoucherOrderServiceImpl();
        RedisIdWorker ids = mock(RedisIdWorker.class);
        // 使用实际 create 方法，验证受理层不会再读申请或调用 MQ 发布。
        ReflectionTestUtils.setField(entry, "seckillVoucherService", vouchers);
        ReflectionTestUtils.setField(entry, "lifecycle", lifecycle);
        ReflectionTestUtils.setField(entry, "reservation", reservation);
        ReflectionTestUtils.setField(entry, "redisIdWorker", ids);
        ReflectionTestUtils.setField(entry, "requests", requests);
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(lifecycle, "redis", redis);
        ReflectionTestUtils.setField(lifecycle, "timeoutSeconds", 600);
        when(redis.hasKey("seckill:stock:8")).thenReturn(true);
        when(ids.nextId("order")).thenReturn(1L);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        Result result = entry.seckillVoucher(8L);
        assertTrue(result.getSuccess());
        assertEquals("1", result.getData());
        verify(vouchers).getById(8L);
        verify(requests).insert(argThat(value -> "NONE".equals(value.getReservationState())));
        verifyNoMoreInteractions(requests, vouchers);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void obviousSoldOutAndDuplicateRequestsDoNotWriteApplications(long code) {
        VoucherOrderServiceImpl entry = new VoucherOrderServiceImpl();
        SeckillRequestService stateMachine = mock(SeckillRequestService.class);
        ReflectionTestUtils.setField(entry, "seckillVoucherService", vouchers);
        ReflectionTestUtils.setField(entry, "lifecycle", stateMachine);
        ReflectionTestUtils.setField(entry, "reservation", reservation);
        when(reservation.check(7L, voucher)).thenReturn(code);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        assertFalse(entry.seckillVoucher(8L).getSuccess());
        verify(stateMachine, never()).create(anyLong(), anyLong(), anyLong());
    }

    @Test
    void successfulPublicationUsesClaimWithoutRereadingApplication() {
        request.setState("READY");
        SeckillOrderPublisher publisher = new SeckillOrderPublisher();
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "requests", requests);
        ReflectionTestUtils.setField(publisher, "template", mq);
        ReflectionTestUtils.setField(publisher, "topic", "test-order");
        when(requests.claimSend(1L)).thenReturn(1);
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(eq("test-order"), any(Message.class))).thenReturn(result);
        publisher.publish(request);
        verify(requests).claimSend(1L);
        verify(requests).sent(eq(1L), anyInt());
        verifyNoMoreInteractions(requests);
    }

    @Test
    void lostPublishLeaseDoesNotSendMessage() {
        request.setState("READY");
        SeckillOrderPublisher publisher = new SeckillOrderPublisher();
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "requests", requests);
        ReflectionTestUtils.setField(publisher, "template", mq);
        publisher.publish(request);
        verifyNoInteractions(mq);
    }

    @Test
    void compensationAndBackoffFailureDoNotSkipOtherRequests() {
        SeckillRecovery recovery = new SeckillRecovery();
        SeckillRequestService stateMachine = mock(SeckillRequestService.class);
        ReflectionTestUtils.setField(recovery, "requests", requests);
        ReflectionTestUtils.setField(recovery, "lifecycle", stateMachine);
        when(requests.expired(anyString())).thenReturn(Collections.emptyList());
        SeckillRequest other = new SeckillRequest();
        other.setOrderId(2L);
        when(requests.due("CANCELLING")).thenReturn(Arrays.asList(request, other));
        doThrow(new IllegalStateException("Redis断开")).when(stateMachine).compensate(1L);
        when(requests.defer(1L)).thenThrow(new IllegalStateException("退避写入失败"));
        recovery.recover();
        verify(stateMachine).compensate(2L);
    }
}
