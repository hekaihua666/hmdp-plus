package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.MerchantDTO;
import com.hmdp.dto.MerchantLoginDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Merchant;
import com.hmdp.mapper.MerchantMapper;
import com.hmdp.service.IMerchantService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 独立 B 端登录：随机 token + Redis，密码使用 BCrypt。 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements IMerchantService {
    public static final String TOKEN_PREFIX = "login:merchant:";
    public static final long SESSION_MINUTES = 120;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result login(MerchantLoginDTO input) {
        if (input == null || input.getUsername() == null || input.getPassword() == null
                || !input.getUsername().matches("[a-zA-Z0-9_]{3,32}")
                || input.getPassword().length() < 8 || input.getPassword().length() > 64
                // BCrypt 只处理前72字节；多字节密码超限必须拒绝，不能静默截断。
                || input.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return Result.fail("请输入3至32位字母数字下划线账号和8至64位密码");
        }
        Merchant merchant = lambdaQuery().eq(Merchant::getUsername, input.getUsername()).one();
        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())
                || !passwordEncoder.matches(input.getPassword(), merchant.getPasswordHash())) {
            return Result.fail("账号或密码错误，或账号已停用");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        // Redis保存id:登录版本，封禁时数据库版本递增，旧token在解封后也无法恢复。
        stringRedisTemplate.opsForValue().set(TOKEN_PREFIX + token,
                merchant.getId() + ":" + versionOf(merchant),
                SESSION_MINUTES, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public MerchantDTO authenticate(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}")) return null;
        String key = TOKEN_PREFIX + token;
        String id = stringRedisTemplate.opsForValue().get(key);
        if (id == null) return null;
        // 兼容升级前仅存id的会话，视为版本0；封禁一次后这些旧会话同样失效。
        if (!id.matches("[0-9]+(?::[0-9]+)?")) { stringRedisTemplate.delete(key); return null; }
        String[] identity = id.split(":");
        Merchant merchant;
        long version;
        try {
            merchant = getById(Long.valueOf(identity[0]));
            version = identity.length == 2 ? Long.parseLong(identity[1]) : 0;
        } catch (NumberFormatException invalid) { stringRedisTemplate.delete(key); return null; }
        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())
                || version != versionOf(merchant)) {
            stringRedisTemplate.delete(key);
            return null;
        }
        // 固定两小时有效期，不在请求中续期，避免退出与续期竞争使登录态复活。
        return MerchantDTO.from(merchant);
    }

    /** 旧数据库迁移后的默认值为0，测试替身或旧对象的null也按0处理。 */
    private long versionOf(Merchant merchant) {
        return merchant.getSessionVersion() == null ? 0 : merchant.getSessionVersion();
    }

    @Override
    public void logout(String token) {
        if (token != null && token.matches("[a-f0-9]{32}")) {
            stringRedisTemplate.delete(TOKEN_PREFIX + token);
        }
    }
}
