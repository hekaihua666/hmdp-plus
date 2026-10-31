package com.hmdp.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 密码加密工具（MD5 + 随机盐）。
 *
 * <p>存储格式：{盐}@{MD5(明文+盐)}。每次加密随机生成 20 位盐，因此同样的明文
 * 每次得到的密文都不同（防彩虹表攻击）。校验时用密文里带出的盐重新计算比对。</p>
 *
 * <p>注意：这是课程演示用的「MD5+盐」方案，真实生产建议改用 BCrypt（自带盐、
 * 且可配置计算成本，抗暴力破解更强），本项目的商户/平台端已经使用 BCrypt。</p>
 */
public class PasswordEncoder {

    /** 加密：随机生成盐，返回「盐@MD5(密码+盐)」格式的密文。 */
    public static String encode(String password) {
        // 生成盐
        String salt = RandomUtil.randomString(20);
        // 加密
        return encode(password,salt);
    }

    /** 用指定盐加密：返回「盐@MD5(密码+盐)」，供 encode 和 matches 复用。 */
    private static String encode(String password, String salt) {
        // 加密
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验明文密码是否匹配密文。
     * 做法：从密文里拆出盐 → 用同一盐对明文再算一次 MD5 → 比对结果是否一致。
     */
    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if(!encodedPassword.contains("@")){
            throw new RuntimeException("密码格式不正确！");
        }
        String[] arr = encodedPassword.split("@");
        // 获取盐
        String salt = arr[0];
        // 比较：用盐对明文重算，看是否等于密文
        return encodedPassword.equals(encode(rawPassword, salt));
    }
}
