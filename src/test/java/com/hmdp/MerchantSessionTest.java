package com.hmdp;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.MerchantLoginDTO;
import com.hmdp.entity.Merchant;
import com.hmdp.mapper.MerchantMapper;
import com.hmdp.service.impl.MerchantServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真正执行密码哈希匹配和会话生命周期；数据库、Redis 网络访问使用替身。 */
class MerchantSessionTest {
    private MerchantServiceImpl service;
    private MerchantMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> values;
    private Merchant merchant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"merchant"),Merchant.class);
        service=new MerchantServiceImpl();mapper=mock(MerchantMapper.class);redis=mock(StringRedisTemplate.class);
        values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service,"baseMapper",mapper);ReflectionTestUtils.setField(service,"stringRedisTemplate",redis);
        merchant=new Merchant();merchant.setId(7L);merchant.setUsername("merchant_a");merchant.setName("商户甲");merchant.setStatus(1);
        merchant.setPasswordHash(new BCryptPasswordEncoder().encode("test-password"));
        when(mapper.selectOne(any())).thenReturn(merchant);when(mapper.selectById(7L)).thenReturn(merchant);
    }

    @Test
    void validLoginStoresOnlyMerchantIdWithExpiration() {
        MerchantLoginDTO input=new MerchantLoginDTO();input.setUsername("merchant_a");input.setPassword("test-password");
        String token=(String)service.login(input).getData();assertNotNull(token);
        verify(values).set("login:merchant:"+token,"7:0",120L,TimeUnit.MINUTES);
    }

    @Test
    void invalidPasswordAndDisabledAccountDoNotCreateSession() {
        MerchantLoginDTO input=new MerchantLoginDTO();input.setUsername("merchant_a");input.setPassword("wrong-password");
        assertFalse(service.login(input).getSuccess());merchant.setStatus(0);input.setPassword("test-password");
        assertFalse(service.login(input).getSuccess());verifyNoInteractions(values);
    }

    @Test
    void oversizedUtf8PasswordIsRejectedBeforeDatabaseAccess() {
        MerchantLoginDTO input=new MerchantLoginDTO();input.setUsername("merchant_a");
        input.setPassword(String.join("", java.util.Collections.nCopies(25,"密")));
        assertFalse(service.login(input).getSuccess());verifyNoInteractions(mapper,values);
    }

    @Test
    void expiredOrConsumerTokenDoesNotCreateIdentity() {
        assertNull(service.authenticate("0123456789abcdef0123456789abcdef"));
        assertNull(service.authenticate("invalid"));
    }

    @Test
    void disablingMerchantRevokesPreviouslyIssuedToken() {
        String token="0123456789abcdef0123456789abcdef";
        when(values.get("login:merchant:"+token)).thenReturn("7");
        assertEquals(7L,service.authenticate(token).getId());merchant.setStatus(0);
        assertNull(service.authenticate(token));verify(redis).delete("login:merchant:"+token);
    }

    @Test
    void logoutDeletesOnlyMerchantSession() {
        String token="0123456789abcdef0123456789abcdef";service.logout(token);
        verify(redis).delete("login:merchant:"+token);
    }

    @Test
    void unbanningDoesNotReviveUnvisitedOldSessions() {
        String token="0123456789abcdef0123456789abcdef";
        when(values.get("login:merchant:"+token)).thenReturn("7:0");
        merchant.setSessionVersion(1L); merchant.setStatus(1);
        assertNull(service.authenticate(token));
        verify(redis).delete("login:merchant:"+token);
    }

    @Test
    void freshSessionWorksAfterVersionIncrease() {
        String token="0123456789abcdef0123456789abcdef";
        merchant.setSessionVersion(1L);
        when(values.get("login:merchant:"+token)).thenReturn("7:1");
        assertEquals(7L,service.authenticate(token).getId());
        verify(redis,never()).delete(anyString());
    }
}
