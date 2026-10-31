package com.hmdp;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.AdminDTO;
import com.hmdp.dto.AdminLoginDTO;
import com.hmdp.entity.Admin;
import com.hmdp.mapper.AdminMapper;
import com.hmdp.service.IMerchantService;
import com.hmdp.service.impl.AdminServiceImpl;
import com.hmdp.utils.AdminHolder;
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

/** 执行真实BCrypt、会话与服务身份检查；数据库和Redis网络用替身，不改本机业务数据。 */
class AdminSessionTest {
    private AdminServiceImpl service;
    private AdminMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> values;
    private IMerchantService merchants;
    private Admin admin;
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"admin"),Admin.class);
        service=new AdminServiceImpl();mapper=mock(AdminMapper.class);redis=mock(StringRedisTemplate.class);
        values=mock(ValueOperations.class);merchants=mock(IMerchantService.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        ReflectionTestUtils.setField(service,"stringRedisTemplate",redis);
        ReflectionTestUtils.setField(service,"merchantService",merchants);
        admin=new Admin();admin.setId(9L);admin.setUsername("admin");admin.setStatus(1);
        admin.setPasswordHash(new BCryptPasswordEncoder().encode("platform-test"));
        when(mapper.selectOne(any())).thenReturn(admin);when(mapper.selectById(9L)).thenReturn(admin);
    }
    @AfterEach void clean() { AdminHolder.remove(); }
    private AdminLoginDTO input(String password) {
        AdminLoginDTO input=new AdminLoginDTO();input.setUsername("admin");input.setPassword(password);return input;
    }
    @Test void loginCreatesOnlyIndependentFixedLifetimeSession() {
        String token=(String)service.login(input("platform-test")).getData();assertNotNull(token);
        verify(values).set("login:admin:"+token,"9",120L,TimeUnit.MINUTES);
    }
    @Test void wrongPasswordAndDisabledAdminCannotLogin() {
        assertFalse(service.login(input("wrong-password")).getSuccess());
        admin.setStatus(0);assertFalse(service.login(input("platform-test")).getSuccess());
        verifyNoInteractions(values);
    }
    @Test void oversizedUtf8PasswordIsRejectedBeforeStorageAccess() {
        assertFalse(service.login(input(String.join("",java.util.Collections.nCopies(25,"密")))).getSuccess());
        verifyNoInteractions(mapper,values);
    }
    @Test void adminStatusRevokesExistingSession() {
        String token="0123456789abcdef0123456789abcdef";
        when(values.get("login:admin:"+token)).thenReturn("9");
        assertEquals(9L,service.authenticate(token).getId());admin.setStatus(0);
        assertNull(service.authenticate(token));verify(redis).delete("login:admin:"+token);
    }
    @Test void logoutDeletesOnlyAdminSession() {
        String token="0123456789abcdef0123456789abcdef";service.logout(token);
        verify(redis).delete("login:admin:"+token);
    }
    @Test void businessServiceCannotBeCalledWithoutAdminIdentity() {
        assertThrows(IllegalStateException.class,()->service.listMerchants(null,null,1,20));
        assertThrows(IllegalStateException.class,()->service.changeMerchantStatus(1L,0));
        verifyNoInteractions(merchants);
    }
    @Test void invalidStatusIsRejectedBeforeMerchantAccess() {
        AdminDTO identity=new AdminDTO();identity.setId(9L);AdminHolder.save(identity);
        assertThrows(IllegalArgumentException.class,()->service.changeMerchantStatus(1L,2));
        assertThrows(IllegalArgumentException.class,()->service.changeMerchantStatus(1L,null));
        assertThrows(IllegalArgumentException.class,()->service.listMerchants(null,null,0,20));
        verifyNoInteractions(merchants);
    }
}
