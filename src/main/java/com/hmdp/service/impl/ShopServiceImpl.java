package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.utils.MerchantHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.entity.Shop;
import com.hmdp.event.ShopChangedEvent;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopSyncEventService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ShopSyncEventService shopSyncEventService;

    /** 后台直接读数据库，避免编辑表单拿到消费者缓存里的旧值。 */
    @Override
    public Result queryOwnShop() {
        Shop shop = baseMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOwnerId, MerchantHolder.requireId()));
        return shop == null ? Result.fail("账号尚未绑定店铺，请联系维护人员") : Result.ok(shop);
    }

    /** 基于当前商户身份读取自己的店铺实体；未绑定返回 null。供发券等后台业务复用。 */
    @Override
    public Shop queryOwnShopEntity() {
        return baseMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOwnerId, MerchantHolder.requireId()));
    }

    /**
     * 一账号一店的全表单更新：身份确定目标，SQL 再次限定归属。
     * 行锁保证本事务读取的旧分类与更新衔接；提交后删除 Redis 缓存，ES 由事务内写入的 Outbox 驱动。
     */
    @Override
    @Transactional
    public Result updateOwnShop(ShopUpdateDTO input) {
        Long merchantId = MerchantHolder.requireId();
        validateShopEdit(input);
        Shop previous = baseMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getOwnerId, merchantId).last("FOR UPDATE"));
        if (previous == null) return Result.fail("账号尚未绑定店铺，请联系维护人员");
        LambdaUpdateWrapper<Shop> update = new LambdaUpdateWrapper<Shop>()
                .eq(Shop::getId, previous.getId()).eq(Shop::getOwnerId, merchantId)
                .set(Shop::getName, input.getName().trim()).set(Shop::getTypeId, input.getTypeId())
                .set(Shop::getImages, input.getImages().trim()).set(Shop::getArea, trim(input.getArea()))
                .set(Shop::getAddress, input.getAddress().trim()).set(Shop::getX, input.getX())
                .set(Shop::getY, input.getY()).set(Shop::getAvgPrice, input.getAvgPrice())
                .set(Shop::getOpenHours, trim(input.getOpenHours()));
        if (baseMapper.update(null, update) != 1) {
            throw new IllegalStateException("店铺更新数量异常");
        }
        // 两件事：①发内存事件让提交后监听器删 Redis 详情缓存 ②落 Outbox 记录走 MQ 可靠同步 ES。
        eventPublisher.publishEvent(new ShopChangedEvent(previous.getId()));
        shopSyncEventService.record(previous.getId());
        return Result.ok();
    }

    /** 验证完整编辑表单；可空字段也有长度限制，数据库约束不充当业务错误提示。 */
    private void validateShopEdit(ShopUpdateDTO input) {
        if (input == null) throw new IllegalArgumentException("店铺信息不能为空");
        text(input.getName(), "店名", 128, true);
        text(input.getAddress(), "地址", 255, true);
        text(input.getImages(), "图片地址", 1024, true);
        text(input.getArea(), "商圈", 128, false);
        text(input.getOpenHours(), "营业时间", 32, false);
        if (input.getTypeId() == null || input.getTypeId() <= 0
                || shopTypeService.getById(input.getTypeId()) == null) {
            throw new IllegalArgumentException("请选择有效的店铺分类");
        }
        if (input.getAvgPrice() == null || input.getAvgPrice() < 0 || input.getAvgPrice() > 1000000) {
            throw new IllegalArgumentException("人均价格须为0至1000000的整数");
        }
        if (input.getX() == null || input.getY() == null || !Double.isFinite(input.getX())
                || !Double.isFinite(input.getY()) || Math.abs(input.getX()) > 180 || Math.abs(input.getY()) > 90) {
            throw new IllegalArgumentException("经度范围为[-180,180]，纬度范围为[-90,90]");
        }
        for (String url : input.getImages().split(",", -1)) {
            // 只保存地址，不由服务器抓取外部图片；禁止脚本协议和协议相对 URL。
            if (!url.matches("(?:https?://|/(?!/))[^\\s,]+")) {
                throw new IllegalArgumentException("图片须为http(s)地址或以单个/开头的本地路径，多图用逗号分隔");
            }
        }
    }

    private static void text(String value, String label, int max, boolean required) {
        if (required && (value == null || value.trim().isEmpty())) throw new IllegalArgumentException(label + "不能为空");
        if (value != null && value.length() > max) throw new IllegalArgumentException(label + "长度不得超过" + max + "字");
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }

    /**
     * 根据 id 查询店铺详情（C 端核心接口，体现缓存三问的解决方案）。
     *
     * <p>当前启用「缓存穿透」方案（queryWithPassThrough）；下面注释掉的另两行
     * 是「缓存击穿」的两种可选方案，讲解缓存三问时切换用：</p>
     * <ul>
     *   <li>穿透（默认）：缓存空值 ""，挡掉对不存在 id 的重复查询</li>
     *   <li>击穿-互斥锁：热点 key 过期时，只放一个请求查库，其余休眠重试</li>
     *   <li>击穿-逻辑过期：热点 key 过期先返回旧数据，后台异步重建</li>
     * </ul>
     *
     * @param id 店铺 id
     * @return 店铺详情；不存在返回失败
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透：缓存空值。缓存 miss 时查库，查不到就写空串 "" 缓存 2 分钟，
        // 下次再来查这个不存在的 id 时，命中空串直接返回 null，不再打数据库。
        Shop shop = cacheClient
                .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿：热点 key 过期时用 SETNX 抢锁，抢到的查库重建，抢不到的 sleep 后重试。
        // Shop shop = cacheClient
        //         .queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿：key 永不物理删除，过期字段存在 value 里；过期时先返回旧数据，
        // 再异步开线程查库重建，用户体验最好，但需要数据预热。
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 受控维护/回归测试的更新入口，不映射 HTTP；商户编辑使用 updateOwnShop 白名单。
     *
     * <p>提交后同步避免回滚副作用，但ES 改走持久 Outbox，Redis 继续由提交后事件失效：</p>
     * <ul>
     *   <li>事务内改商户、写 Outbox 并发布缓存事件，不直接动 Redis/ES；</li>
     *   <li>缓存监听器用 @TransactionalEventListener(AFTER_COMMIT) 在事务提交后才删除缓存，
     *       数据库回滚时监听器不执行，避免 Redis/ES 留下脏数据；</li>
     *   <li>提交后失效详情缓存；MQ 消费者按数据版本同步 ES 的分类、坐标等字段。</li>
     * </ul>
     *
     * @param shop 店铺数据，必须带 id
     * @return 成功返回 ok；id 为空或记录不存在返回失败
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        // ① 更新必须有 id（否则不知道改哪一行）
        if (shop.getId() == null)
            return Result.fail("店铺id不能为空");

        // ② 先确认数据库中存在该店铺。
        //    getById 是 MyBatis-Plus 继承方法，查的是 MySQL（数据库才是权威源头，不能查 Redis）。
        Shop previous = getById(shop.getId());
        // ③ 记录不存在，或更新失败（updateById 返回 false），直接失败
        if (previous == null || !updateById(shop)) {
            return Result.fail("店铺更新失败");
        }
        // ④ 发内存事件（提交后删缓存）+ 落 Outbox（MQ 同步 ES），两条路径各司其职。
        eventPublisher.publishEvent(new ShopChangedEvent(shop.getId()));
        shopSyncEventService.record(shop.getId());

        return Result.ok();

    }

    /**
     * 受控维护/回归测试的新增入口，不提供公开注册或自主开店 HTTP 接口。
     *
     * <p>save 写库成功后发布事件；事务提交后监听器负责失效详情缓存，ES 同步由 Outbox + MQ 完成。
     * 数据库回滚时事件监听器不会执行，避免留下 Redis / ES 孤立数据。</p>
     *
     * @param shop 店铺数据（不含 id，由数据库自增生成）
     * @return 新增成功后的店铺 id
     */
    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        // ① 写入数据库（MyBatis-Plus 的 save，执行 INSERT，回填自增 id）
        if (!save(shop)) {
            return Result.fail("新增店铺失败");
        }
        // ② 发内存事件（提交后删缓存）+ 落 Outbox（MQ 同步 ES）。
        eventPublisher.publishEvent(new ShopChangedEvent(shop.getId()));
        shopSyncEventService.record(shop.getId());
        return Result.ok(shop.getId());
    }

}
