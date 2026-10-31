package com.hmdp.service;

import static com.hmdp.constants.SeckillConstants.CANCELLING;
import static com.hmdp.constants.SeckillConstants.FAILED;
import static com.hmdp.constants.SeckillConstants.INIT;
import static com.hmdp.constants.SeckillConstants.READY;
import static com.hmdp.constants.SeckillConstants.RESERVATION_HELD;
import static com.hmdp.constants.SeckillConstants.RESERVATION_NONE;
import static com.hmdp.constants.SeckillConstants.RESERVATION_UNKNOWN;
import static com.hmdp.constants.SeckillConstants.SUCCEEDED;
import static com.hmdp.constants.SeckillConstants.TIMEOUT_REASON;
import static com.hmdp.utils.RedisConstants.SECKILL_INITIALIZE_LOCK;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderCreateMessage;
import java.time.LocalDateTime;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 秒杀申请的状态机，管一条申请从受理到成功/失败的整条生命线。
 * 核心套路：所有状态裁决都靠“申请行锁”串行，扣库存、写订单、标记成功放在同一个事务里提交；
 * 要终止时先落 CANCELLING，再由补偿任务幂等释放 Redis，释放成功才提交 FAILED。
 */
@Service
public class SeckillRequestService {
    @Resource private SeckillRequestMapper requests;
    @Resource private VoucherOrderMapper orders;
    @Resource private ISeckillVoucherService vouchers;
    @Resource private SeckillReservationService reservation;
    @Resource private StringRedisTemplate redis;
    @Resource private RedissonClient redisson;
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactionManager;

    @Value("${hmdp.mq.request-timeout-seconds:600}")
    private int timeoutSeconds;

    /** 启动时自检秒杀表结构：申请表要能查到字段、订单表要有一人一券的唯一索引，缺了就直接拒绝启动。
     *  这样把"表没建好"这类问题挡在启动阶段，而不是等抢购跑到一半才发现。 */
    @PostConstruct
    public void verifySchema() {
        try {
            // 先探一下申请表能不能正常查出这两列，表不存在会直接抛异常。
            jdbc.queryForList("SELECT order_id,reservation_state FROM tb_seckill_request LIMIT 1");
            // 查订单表有没有 (user_id,voucher_id) 唯一索引，没有它"一人一券"就防不住重复下单。
            Integer indexes = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT INDEX_NAME,NON_UNIQUE,"
                    + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_list "
                    + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME='tb_voucher_order' GROUP BY INDEX_NAME,NON_UNIQUE "
                    + "HAVING NON_UNIQUE=0 AND columns_list IN "
                    + "('user_id,voucher_id','voucher_id,user_id')) checks_", Integer.class);
            if (indexes == null || indexes == 0) {
                throw new IllegalStateException("缺少一人一券唯一约束");
            }
        } catch (Exception error) {
            throw new IllegalStateException(
                    "秒杀MQ表结构未就绪，请执行 scripts/setup_seckill_mq.py --apply", error);
        }
    }

    /** Redis 里的库存数字丢了，就把它补回来。补之前必须先确认"没有正在进行的抢购"，否则会把旧数字盖在已预占的库存上，造成超卖。
     *  用一把 Redisson 锁把"查有没有在途申请 + 回填库存"串行起来，避免多个实例同时补导致重复覆盖。 */
    public void initializeInventory(SeckillVoucher voucher) {
        String key = SECKILL_STOCK_KEY + voucher.getVoucherId();
        // 库存键还在就直接返回，说明数据没丢，不用补。
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        RLock lock = redisson.getLock(SECKILL_INITIALIZE_LOCK + voucher.getVoucherId());
        lock.lock();
        try {
            // 拿锁后再查一次，防止等锁期间别的实例已经把库存补好了。
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                return;
            }
            // 查有没有还在推进的申请；有的话数据库里的旧库存数字可能慢半拍，补进去会算错库存。
            Long active = requests.selectCount(new LambdaQueryWrapper<SeckillRequest>()
                    .eq(SeckillRequest::getVoucherId, voucher.getVoucherId())
                    .in(SeckillRequest::getState, INIT, READY, CANCELLING));
            if (active > 0) {
                throw new IllegalStateException("存在处理中申请，库存状态需核查");
            }
            // 以数据库最新库存为准回填，而不是用入口传进来的可能已过期的 voucher。
            SeckillVoucher latest = vouchers.getById(voucher.getVoucherId());
            if (latest == null) {
                throw new IllegalArgumentException("优惠券不存在");
            }
            redis.opsForValue().setIfAbsent(key, latest.getStock().toString());
        } finally {
            lock.unlock();
        }
    }

    /** 申请是不是已经超过处理期限了。这是显式写进 deadline 的业务规则，而不是“没等到消息”的猜测。
     *  超期的申请不管走到哪一步，都应该进入终止流程。 */
    private boolean expired(SeckillRequest request) {
        return !LocalDateTime.now().isBefore(request.getDeadline());
    }

    /** HTTP 入口只负责落一条受理记录，先不碰 Redis、不发消息。此时还没尝试预占，可以明确把预占态记成 NONE。
     *  落库成功后这条申请就成了后续预占、发送、补偿的持久化锚点。 */
    @Transactional
    public void create(Long orderId, Long userId, Long voucherId) {
        LocalDateTime now = LocalDateTime.now();
        // 创建申请记录
        SeckillRequest request = new SeckillRequest();
        request.setOrderId(orderId);
        request.setUserId(userId);
        request.setVoucherId(voucherId);
        request.setState(INIT);
        // 刚受理时确定还没扣过库存，所以是 NONE；后面扣之前会先改成 UNKNOWN。
        request.setReservationState(RESERVATION_NONE);
        request.setSendAttempts(0);
        request.setCreateTime(now);
        request.setUpdateTime(now);
        // 下次处理时间先设成“现在”，让定时任务立刻能扫到它。
        request.setNextSendTime(now);
        // 处理期限 = 受理时刻 + 配置的超时秒数，超过这个点还没落单就算失败。
        request.setDeadline(now.plusSeconds(timeoutSeconds));
        requests.insert(request);
    }

    /** 推进预占，分两步走：第一步先记一个“结果待定”的记号（UNKNOWN），第二步再真正去扣 Redis 库存。
     *  分两步是因为 Redis 和数据库不在一个事务里，先留记号能保证中间崩了也能靠记号恢复，不会误判成“从没扣过”。 */
    public void prepare(Long orderId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // 开独立事务，保证下面这步“记 UNKNOWN”能自己单独提交，不被外层事务一起卷回滚。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean firstAttempt = transaction.execute(status -> {
            SeckillRequest request = requests.lock(orderId);
            // 不是 INIT 就不处理（可能已经被别的实例推进或终止了）。
            if (request == null || !INIT.equals(request.getState())) {
                return null;
            }
            // 已经超时，直接终止，不再预占。
            if (expired(request)) {
                cancelling(request, TIMEOUT_REASON);
                return null;
            }
            // 关键：先把 NONE 改成 UNKNOWN 并提交。这样哪怕后面扣 Redis 时崩了，
            // 数据库里也留着“可能扣过”的记号，补偿时不会误判成“从没扣过”。
            if (RESERVATION_NONE.equals(request.getReservationState())) {
                request.setReservationState(RESERVATION_UNKNOWN);
                request.setNextSendTime(LocalDateTime.now().plusSeconds(10));
                save(request);
                return true;
            }
            // 租期内可能仍有首次调用在执行，其他实例暂不抢先核查其 Redis 凭据。
            if (request.getNextSendTime() != null
                    && LocalDateTime.now().isBefore(request.getNextSendTime())) {
                return null;
            }
            return false;
        });
        // 只有 firstAttempt 明确是 true/false（不是 null 跳过）才进第二段真正扣库存。
        if (firstAttempt != null) {
            transaction.executeWithoutResult(status -> prepareLocked(orderId, firstAttempt));
        }
    }

    /** 第二段：真正去扣 Redis 库存。第一段已经把预占态记成 UNKNOWN 了，这里拿行锁重新读一次申请，判断是首次还是重试，再决定扣不扣。
     *  扣成功就把申请推进到 READY 等发送；被明确拒绝就走终止；扣的结果说不清就抛异常留待恢复。 */
    private void prepareLocked(Long orderId, boolean firstAttempt) {
        SeckillRequest request = requests.lock(orderId);
        if (request == null || !INIT.equals(request.getState())) {
            // 首次调用尚未执行 Lua 就被取消，可以明确确认此次没有预占。
            if (request != null && firstAttempt && CANCELLING.equals(request.getState())
                    && RESERVATION_UNKNOWN.equals(request.getReservationState())) {
                request.setReservationState(RESERVATION_NONE);
                save(request);
            }
            return;
        }
        if (expired(request)) {
            abandonBeforeReserve(request, firstAttempt, TIMEOUT_REASON);
            return;
        }
        SeckillVoucher voucher = vouchers.getById(request.getVoucherId());
        if (voucher == null) {
            abandonBeforeReserve(request, firstAttempt, "优惠券不存在");
            return;
        }
        // 扣库存
        long code = reservation.reserve(request, voucher, firstAttempt);
        // 扣库存脚本返回码：0=成功，1=库存不足，2=不能重复下单，3=优惠券不在抢购时间内，
        // 4=未知错误，5=库存数据缺失，6=预占证据待恢复核查
        if (code == 5 && firstAttempt) {
            // 脚本明确在扣减前发现库存缺失，此次结果已知；恢复库存后可以重新尝试首次预占。
            request.setReservationState(RESERVATION_NONE);
            request.setLastError("库存数据缺失，等待恢复核查");
            request.setNextSendTime(LocalDateTime.now().plusSeconds(10));
            save(request);
            return;
        }
        if (code == 5 || code == 6) {
            throw new IllegalStateException("预占证据待恢复核查，返回码=" + code);
        }
        if (code != 0) {
            // 明确的拒绝结果表示本次不再持有预占；只更新一次终止状态。
            request.setReservationState(RESERVATION_NONE);
            cancelling(request, rejectionReason(code));
            return;
        }
        request.setReservationState(RESERVATION_HELD);
        request.setState(READY);
        request.setNextSendTime(LocalDateTime.now());
        save(request);
    }

    /** 在真正调用 Lua 扣库存之前就发现要终止了。首次调用能确定“还没扣过”，可以放心把预占态改回 NONE；
     *  但重试时上一次可能已经扣过库存，就必须保留 UNKNOWN 证据等恢复核查，不能擅自改回 NONE。 */
    private void abandonBeforeReserve(SeckillRequest request, boolean firstAttempt, String reason) {
        if (firstAttempt) {
            request.setReservationState(RESERVATION_NONE);
        }
        cancelling(request, reason);
    }

    /** 统一落库入口：先刷新更新时间再写回。之所以抽出来，是避免每个状态流转的地方都重复写这两行。
     *  所有要持久化申请最新状态的改动都走这里，保证更新时间不会漏刷。 */
    private void save(SeckillRequest request) {
        request.setUpdateTime(LocalDateTime.now());
        requests.updateById(request);
    }

    /** 把 Lua 返回码翻译成人话的拒绝原因，给前端展示用。环境异常（5/6）不在这里翻译，由调用方抛异常处理。
     *  只认 1/2/3 这三种明确的业务拒绝，其余一律按“申请已终止”兜底。 */
    public static String rejectionReason(long code) {
        if (code == 1) {
            return "库存不足";
        }
        if (code == 2) {
            return "不能重复下单";
        }
        if (code == 3) {
            return "优惠券不在抢购时间内";
        }
        return "申请已终止";
    }

    @Transactional
    public void cancel(Long orderId, String reason) {
        SeckillRequest request = requests.lock(orderId);
        if (request != null && (INIT.equals(request.getState()) || READY.equals(request.getState()))) {
            cancelling(request, reason);
        }
    }

    /** 调用方已经持有申请行锁时，提交“终止”裁决：把状态改成 CANCELLING、记下原因，并让下次处理时间立刻到期。
     *  这样独立的补偿任务马上就能扫到它、去把 Redis 里预占的库存还回去。 */
    private void cancelling(SeckillRequest request, String reason) {
        request.setState(CANCELLING);
        request.setLastError(reason);
        request.setNextSendTime(LocalDateTime.now());
        save(request);
    }

    /** 处理 CANCELLING 状态的申请：先把 Redis 里的预占释放掉，释放成功才把状态改成 FAILED。
     *  只有 Lua 明确返回“释放完成”才提交 FAILED；一旦释放结果存疑就抛异常回滚，让状态继续停在 CANCELLING 等下一轮重试。 */
    @Transactional
    public void compensate(Long orderId) {
        SeckillRequest request = requests.lock(orderId);
        if (request == null || !CANCELLING.equals(request.getState())) {
            return;
        }
        reservation.release(request);
        request.setState(FAILED);
        request.setReservationState(RESERVATION_NONE);
        save(request);
    }

    /** 消费端的核心落单逻辑：拿行锁读申请，校验消息身份一致、状态是 READY、预占凭据还在，然后扣数据库库存、写订单、把申请改成成功。
     *  一旦返回就表示事务完成，消息会正常确认；可恢复的异常会向上抛给 SDK 触发重试，持久终止则交给独立的补偿任务处理。 */
    @Transactional
    public void consume(OrderCreateMessage message) {
        if (message == null || message.getOrderId() == null) {
            throw new IllegalArgumentException("消息缺少订单身份");
        }
        SeckillRequest request = requests.lock(message.getOrderId());
        if (!message.matches(request)) {
            throw new IllegalArgumentException("消息与申请身份不一致");
        }
        if (SUCCEEDED.equals(request.getState()) || FAILED.equals(request.getState())
                || CANCELLING.equals(request.getState())) {
            return;
        }
        if (!READY.equals(request.getState())) {
            throw new IllegalStateException("申请尚未完成预占");
        }
        if (expired(request)) {
            cancelling(request, TIMEOUT_REASON);
            return;
        }
        if (!reservation.held(request)) {
            throw new IllegalStateException("Redis预占凭据缺失，保留消息等待核查");
        }
        VoucherOrder existing = orders.selectById(request.getOrderId());
        if (existing != null) {
            if (!request.getUserId().equals(existing.getUserId())
                    || !request.getVoucherId().equals(existing.getVoucherId())) {
                throw new IllegalStateException("订单主键与申请身份冲突");
            }
            succeeded(request);
            return;
        }
        // 一人一券由 Lua 归属、申请行锁及数据库唯一约束保障，无需串行同一用户的其他券。
        Long count = orders.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, request.getUserId())
                .eq(VoucherOrder::getVoucherId, request.getVoucherId()));
        if (count > 0) {
            cancelling(request, "该用户已通过另一申请购买此券");
            return;
        }
        boolean deducted = vouchers.lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, request.getVoucherId())
                .gt(SeckillVoucher::getStock, 0)
                .setSql("stock=stock-1").update();
        if (!deducted) {
            cancelling(request, "数据库库存不足");
            return;
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(request.getOrderId());
        order.setUserId(request.getUserId());
        order.setVoucherId(request.getVoucherId());
        order.setStatus(1);
        order.setPayType(1);
        // 唯一冲突必须向外抛出，让此前扣减的数据库库存随事务一起回滚。
        if (orders.insert(order) != 1) {
            throw new IllegalStateException("订单保存失败");
        }
        succeeded(request);
    }

    /** 把申请标记为成功：状态改成 SUCCEEDED、清掉历史错误，并通过 mapper 的条件更新落库。
     *  用 WHERE state='READY' 兜底，避免已经失败或取消的申请被这个更新“复活”。 */
    private void succeeded(SeckillRequest request) {
        request.setState(SUCCEEDED);
        request.setLastError(null);
        requests.succeeded(request.getOrderId());
    }
}
