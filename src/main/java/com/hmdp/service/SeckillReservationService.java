package com.hmdp.service;

import static com.hmdp.constants.SeckillConstants.RESERVATION_UNKNOWN;
import static com.hmdp.utils.RedisConstants.SECKILL_OWNERS_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_REQUEST_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.SeckillVoucher;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import javax.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 专门负责和 Redis 里的秒杀 Lua 脚本打交道：只读预检、幂等预占、幂等释放，都在这里统一构造多键参数去执行脚本。
 *  这么做的目的是把“Lua 脚本怎么传参”这摊脏活收口到一处，上层业务只需要拿到返回码做判断，不用关心 KEYS/ARGV 的顺序。 */
@Service
public class SeckillReservationService {
    private static final DefaultRedisScript<Long> CHECK = script("seckill-check.lua");
    private static final DefaultRedisScript<Long> RESERVE = script("seckill-reserve.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("seckill-release.lua");
    @Resource private StringRedisTemplate redis;

    /** 把 classpath 下的 Lua 文件包装成一个返回 Long 的脚本对象，启动时就编译好，后续反复复用。 */
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    /** 把 Java 时间转成秒级时间戳字符串，给 Lua 判断活动时间窗用。
     *  转成字符串是为了让 Lua 里直接用 tonumber 解析，避免类型对不上。 */
    private String epoch(LocalDateTime time) {
        return Long.toString(time.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    /** 只读预检：在 HTTP 受理阶段先筛掉明显售罄、重复下单、不在活动时间的请求，减少写 MySQL 的无效申请。
     *  这一步只看不扣库存，所以叫预检；正式预占还会在持久化受理之后重新裁决一遍。返回码：0 可继续 / 1 售罄 / 2 重复 / 3 不在活动时间 / 5 库存缺失。 */
    public long check(Long userId, SeckillVoucher voucher) {
        Long result = redis.execute(CHECK, Arrays.asList(
                        SECKILL_STOCK_KEY + voucher.getVoucherId(),
                        SECKILL_OWNERS_KEY + voucher.getVoucherId()),
                userId.toString(), epoch(voucher.getBeginTime()), epoch(voucher.getEndTime()));
        if (result == null) {
            throw new IllegalStateException("库存预检结果未知");
        }
        return result;
    }

    /** 真正扣库存的幂等预占：执行 seckill-reserve.lua，用同一 orderId 保证重复调用只成功一次。
     *  调用方必须先落好 UNKNOWN 意图再调这里；异常后靠同一个 orderId 重试恢复。 */
    public long reserve(SeckillRequest request, SeckillVoucher voucher, boolean firstAttempt) {
        Long result = redis.execute(RESERVE, Arrays.asList(
                        SECKILL_STOCK_KEY + request.getVoucherId(),
                        SECKILL_OWNERS_KEY + request.getVoucherId(),
                        SECKILL_REQUEST_KEY + request.getOrderId()),
                request.getUserId().toString(), request.getOrderId().toString(),
                epoch(voucher.getBeginTime()), epoch(voucher.getEndTime()), firstAttempt ? "1" : "0");
        if (result == null) {
            throw new IllegalStateException("Redis预占结果未知，留待恢复");
        }
        return result;
    }

    /** 幂等释放预占：执行 seckill-release.lua，把扣过的库存还回 Redis。只有返回 0 才代表真正释放完成。
     *  凭据不全或结果存疑时直接抛异常，让数据库里继续保留 CANCELLING 状态，等下一轮补偿重试。 */
    public void release(SeckillRequest request) {
        Long result = redis.execute(RELEASE, Arrays.asList(
                        SECKILL_STOCK_KEY + request.getVoucherId(),
                        SECKILL_OWNERS_KEY + request.getVoucherId(),
                        SECKILL_REQUEST_KEY + request.getOrderId()),
                request.getUserId().toString(), request.getOrderId().toString(),
                request.getReservationState() == null ? RESERVATION_UNKNOWN : request.getReservationState());
        if (result == null || result != 0) {
            throw new IllegalStateException("预占释放待核查，返回码=" + result);
        }
    }

    /** 核验预占凭据还在不在：本申请的标记必须是 HELD，且 owners 里该用户当前挂的还得是这个 orderId。
     *  业务行锁已经排除了正常终止的竞争，这里只是再读一次 Redis 确认归属没丢。 */
    public boolean held(SeckillRequest request) {
        return "HELD".equals(redis.opsForValue().get(SECKILL_REQUEST_KEY + request.getOrderId()))
                && request.getOrderId().toString().equals(redis.opsForHash().get(
                        SECKILL_OWNERS_KEY + request.getVoucherId(), request.getUserId().toString()));
    }
}
