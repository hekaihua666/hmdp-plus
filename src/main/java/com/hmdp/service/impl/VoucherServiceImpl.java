package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    /**
     * 商户为自己店铺发券：shopId 由当前商户登录身份确定，不接受前端传入，防越权。
     * type=0 普通券只写 tb_voucher；type=1 秒杀券额外写 tb_seckill_voucher 并预热 Redis 库存。
     */
    @Override
    @Transactional
    public Result addVoucherForOwnShop(Voucher voucher) {
        // ① 用登录身份查自己的店铺（一账号一店），shopId 不信任前端
        Shop ownShop = shopService.queryOwnShopEntity();
        if (ownShop == null) {
            return Result.fail("账号尚未绑定店铺，无法发布优惠券");
        }
        // ② 基础校验：标题、价格必填且合理
        if (voucher == null || voucher.getTitle() == null || voucher.getTitle().trim().isEmpty()) {
            return Result.fail("请填写优惠券标题");
        }
        if (voucher.getPayValue() == null || voucher.getActualValue() == null
                || voucher.getPayValue() < 0 || voucher.getActualValue() <= 0) {
            return Result.fail("请填写合理的支付金额与抵扣金额");
        }
        // ③ 把店铺归属固定为当前商户自己的店铺，忽略任何外部传入的 shopId
        voucher.setShopId(ownShop.getId());
        voucher.setId(null);   // 防止前端伪造主键覆盖
        if (voucher.getType() == null) {
            voucher.setType(0);
        }
        if (voucher.getStatus() == null) {
            voucher.setStatus(1);   // 默认上架
        }

        // ④ 秒杀券走专门逻辑（写库存表 + 预热 Redis），普通券直接保存
        if (Integer.valueOf(1).equals(voucher.getType())) {
            if (voucher.getStock() == null || voucher.getStock() <= 0) {
                return Result.fail("秒杀券库存必须大于0");
            }
            if (voucher.getBeginTime() == null || voucher.getEndTime() == null
                    || !voucher.getEndTime().isAfter(voucher.getBeginTime())) {
                return Result.fail("秒杀券需设置正确的生效与失效时间");
            }
            addSeckillVoucher(voucher);
        } else {
            save(voucher);
        }
        return Result.ok(voucher.getId());
    }

@Override
@Transactional
public void addSeckillVoucher(Voucher voucher) {
    // 保存优惠券
    save(voucher);
    // 保存秒杀信息
    SeckillVoucher seckillVoucher = new SeckillVoucher();
    seckillVoucher.setVoucherId(voucher.getId());
    seckillVoucher.setStock(voucher.getStock());
    seckillVoucher.setBeginTime(voucher.getBeginTime());
    seckillVoucher.setEndTime(voucher.getEndTime());
    seckillVoucherService.save(seckillVoucher);
    // 保存秒杀库存到Redis中
    stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
}


}
