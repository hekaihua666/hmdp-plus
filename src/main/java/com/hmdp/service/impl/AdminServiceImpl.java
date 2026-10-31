package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.*;
import com.hmdp.entity.Admin;
import com.hmdp.entity.Merchant;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.AdminMapper;
import com.hmdp.service.IAdminService;
import com.hmdp.service.IMerchantService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.AdminHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 平台服务保持独立；复用现有MyBatis、Redis与BCrypt，不引入JWT或完整权限系统。 */
@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements IAdminService {
    public static final String TOKEN_PREFIX = "login:admin:";
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private IMerchantService merchantService;
    @Resource private IShopService shopService;
    @Resource private IShopTypeService shopTypeService;

    @Override
    public Result login(AdminLoginDTO input) {
        if (input == null || input.getUsername() == null || input.getPassword() == null
                || !input.getUsername().matches("[a-zA-Z0-9_]{3,32}")
                || input.getPassword().length() < 6 || input.getPassword().length() > 64
                || input.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return Result.fail("请输入3至32位账号和6至64位密码");
        }
        Admin admin = lambdaQuery().eq(Admin::getUsername, input.getUsername()).one();
        if (admin == null || !Integer.valueOf(1).equals(admin.getStatus())
                || !passwordEncoder.matches(input.getPassword(), admin.getPasswordHash())) {
            return Result.fail("账号或密码错误，或账号已停用");
        }
        String token = UUID.randomUUID().toString().replace("-", "");//生成令牌
        // 只保存账号ID；固定两小时，身份与状态每次从数据库确认，不在请求中续期。
        stringRedisTemplate.opsForValue().set(TOKEN_PREFIX + token, admin.getId().toString(), 120, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public AdminDTO authenticate(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}")) return null;
        String key = TOKEN_PREFIX + token;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) return null;
        Admin admin = null;
        try { admin = getById(Long.valueOf(value)); }
        catch (NumberFormatException invalid) { /* 损坏的会话同样失效，不向客户端暴露存储内容。 */ }
        if (admin == null || !Integer.valueOf(1).equals(admin.getStatus())) {
            stringRedisTemplate.delete(key);
            return null;
        }
        return AdminDTO.from(admin);
    }

    @Override
    public void logout(String token) {
        if (token != null && token.matches("[a-f0-9]{32}")) stringRedisTemplate.delete(TOKEN_PREFIX + token);
    }

    @Override
    public Result listMerchants(String keyword, Integer status, int current, int pageSize) {
        AdminHolder.requireId();
        if (current < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("页码须为正数，每页1至100条");
        if (status != null && status != 0 && status != 1) throw new IllegalArgumentException("商户状态只能是0或1");
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 64) throw new IllegalArgumentException("搜索词不能超过64个字符");
        // 先找匹配门店的归属账号，再对账号分页。不能在分页后筛店名，否则总数和页码会错误。
        List<Long> matchingOwners = term.isEmpty() ? Collections.emptyList() : shopService.lambdaQuery()
                .select(Shop::getOwnerId).isNotNull(Shop::getOwnerId)
                .and(query -> query.like(Shop::getName, term).or().like(Shop::getArea, term))
                .list().stream().map(Shop::getOwnerId).distinct().collect(Collectors.toList());
        // 直接限制查询字段，密码与会话版本不进入平台列表的结果对象。
        Page<Merchant> page = merchantService.lambdaQuery()
                .select(Merchant::getId, Merchant::getUsername, Merchant::getName, Merchant::getStatus, Merchant::getCreateTime)
                .and(!term.isEmpty(), query -> {
                    query.like(Merchant::getUsername, term).or().like(Merchant::getName, term);
                    if (!matchingOwners.isEmpty()) query.or().in(Merchant::getId, matchingOwners);
                })
                .eq(status != null, Merchant::getStatus, status)
                .orderByDesc(Merchant::getId).page(new Page<>(current, pageSize));
        IPage<PlatformMerchantDTO> view = page.convert(PlatformMerchantDTO::from);
        if (!view.getRecords().isEmpty()) {
            List<Long> ids = view.getRecords().stream().map(PlatformMerchantDTO::getId).collect(Collectors.toList());
            // 一页账号一次查询关联店铺，避免逐行查询；当前系统的一账号一店约束继续保留。
            Map<Long, Shop> shops = shopService.lambdaQuery().select(Shop::getId, Shop::getName, Shop::getOwnerId,
                            Shop::getImages, Shop::getTypeId, Shop::getArea, Shop::getAddress)
                    .in(Shop::getOwnerId, ids).list().stream().collect(Collectors.toMap(Shop::getOwnerId, shop -> shop));
            Set<Long> typeIds = shops.values().stream().map(Shop::getTypeId).filter(Objects::nonNull).collect(Collectors.toSet());
            // 分类也批量查询，不随行数增加SQL次数；没有关联店铺的账号仍可在平台管理。
            Map<Long, String> typeNames = typeIds.isEmpty() ? Collections.emptyMap() : shopTypeService.listByIds(typeIds)
                    .stream().collect(Collectors.toMap(ShopType::getId, ShopType::getName));
            for (PlatformMerchantDTO merchant : view.getRecords()) {
                Shop shop = shops.get(merchant.getId());
                if (shop != null) {
                    merchant.setShopId(shop.getId());
                    merchant.setShopName(shop.getName());
                    merchant.setShopCover(shop.getImages() == null ? null : shop.getImages().split(",", 2)[0].trim());
                    merchant.setShopTypeName(typeNames.get(shop.getTypeId()));
                    merchant.setShopArea(shop.getArea());
                    merchant.setShopAddress(shop.getAddress());
                }
            }
        }
        return Result.ok(view);
    }

    @Override
    public Result changeMerchantStatus(Long id, Integer status) {
        AdminHolder.requireId();
        if (id == null || id <= 0 || status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("需要有效商户ID，status只能是0或1");
        }
        if (merchantService.getById(id) == null) return Result.fail("商户不存在");
        if (status == 0) {
            // 同一条SQL同时封禁和递增版本；只处理启用账号，重复封禁不再递增。
            // 不扫描Redis所有token，也不存在旧会话在解封后重新有效的问题。
            merchantService.lambdaUpdate().eq(Merchant::getId, id).eq(Merchant::getStatus, 1)
                    .set(Merchant::getStatus, 0).setSql("session_version = session_version + 1").update();
        } else {
            // 解封只恢复账号状态，不回退版本；商户须重新登录获取新版本的token。
            merchantService.lambdaUpdate().eq(Merchant::getId, id).set(Merchant::getStatus, 1).update();
        }
        return Result.ok();
    }
}
