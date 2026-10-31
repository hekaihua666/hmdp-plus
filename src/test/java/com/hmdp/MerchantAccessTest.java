package com.hmdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.WebMvcConfig;
import com.hmdp.controller.user.ShopController;
import com.hmdp.controller.user.VoucherController;
import com.hmdp.controller.admin.MerchantController;
import com.hmdp.controller.admin.MerchantExceptionAdvice;
import com.hmdp.dto.*;
import com.hmdp.interceptor.MerchantLoginInterceptor;
import com.hmdp.interceptor.AdminLoginInterceptor;
import com.hmdp.controller.platform.AdminController;
import com.hmdp.controller.platform.PlatformMerchantController;
import com.hmdp.controller.platform.PlatformExceptionAdvice;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.service.*;
import com.hmdp.utils.MerchantHolder;
import com.hmdp.utils.AdminHolder;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 WebMvcConfig 和两个端的拦截器验证路由隔离，外部存储使用替身。 */
class MerchantAccessTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private IMerchantService merchants;
    private IShopService shops;
    private IAdminService admins;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        merchants = context.getBean(IMerchantService.class); shops = context.getBean(IShopService.class);
        admins = context.getBean(IAdminService.class);
        AdminDTO admin = new AdminDTO(); admin.setId(9L); admin.setUsername("admin");
        when(admins.authenticate("admin-token")).thenReturn(admin);
        when(admins.listMerchants(any(),any(),anyInt(),anyInt())).thenAnswer(invocation -> Result.ok(AdminHolder.requireId()));
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        HashOperations<String,Object,Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes); when(hashes.entries(anyString())).thenReturn(Collections.emptyMap());
        MerchantDTO merchant = new MerchantDTO(); merchant.setId(1L); merchant.setName("商户甲");
        when(merchants.authenticate("merchant-token")).thenReturn(merchant);
        when(shops.queryOwnShop()).thenAnswer(invocation -> Result.ok(MerchantHolder.requireId()));
        when(shops.updateOwnShop(any())).thenReturn(Result.ok());
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void close() { MerchantHolder.remove(); AdminHolder.remove(); context.close(); }

    @Test
    void anonymousAndConsumerTokensCannotEnterMerchantBackend() throws Exception {
        mvc.perform(get("/admin/shop")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/shop").header("authorization", "consumer-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shops);
    }

    @Test
    void merchantIdentityIsAvailableDuringRequestAndClearedAfterwards() throws Exception {
        mvc.perform(get("/admin/shop").header("authorization","merchant-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));
        assertNull(MerchantHolder.get());
        mvc.perform(get("/admin/shop")).andExpect(status().isUnauthorized());
    }

    @Test
    void merchantLoginIsPublicButLogoutIsProtected() throws Exception {
        when(merchants.login(any())).thenReturn(Result.ok("new-token"));
        mvc.perform(post("/admin/merchant/login").contentType("application/json")
                .content("{\"username\":\"merchant_a\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("new-token"));
        mvc.perform(post("/admin/merchant/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/merchant/logout").header("authorization","merchant-token")).andExpect(status().isOk());
        verify(merchants).logout("merchant-token");
    }

    @Test
    void protectedShopFieldsAreRejectedBeforeServiceUpdate() throws Exception {
        for (String field : new String[]{"id","ownerId","owner_id","score","sold","comments","updateTime"}) {
            mvc.perform(put("/admin/shop").header("authorization","merchant-token").contentType("application/json")
                    .content("{\""+field+"\":2}" )).andExpect(status().isBadRequest());
        }
        verify(shops, never()).updateOwnShop(any());
    }

    @Test
    void legacyPublicWritesHaveNoHandlers() throws Exception {
        for (String path : new String[]{"/shop","/voucher","/voucher/seckill"}) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        }
        mvc.perform(put("/shop").contentType("application/json").content("{}"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        verifyNoInteractions(shops, context.getBean(IVoucherService.class));
    }

    @Test
    void merchantTokenDoesNotAuthenticateConsumerOperations() throws Exception {
        mvc.perform(get("/private-consumer").header("authorization","merchant-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformRejectsAnonymousMerchantAndConsumerTokens() throws Exception {
        mvc.perform(get("/platform/merchant")).andExpect(status().isUnauthorized());
        for (String token : new String[]{"merchant-token", "consumer-token"}) {
            mvc.perform(get("/platform/merchant").header("authorization",token)).andExpect(status().isUnauthorized());
            mvc.perform(put("/platform/merchant/1/status").header("authorization",token).contentType("application/json")
                    .content("{\"status\":0}")).andExpect(status().isUnauthorized());
        }
        verify(admins,never()).changeMerchantStatus(any(),any());
    }

    @Test
    void adminIdentityIsAvailableAndDoesNotCrossIntoOtherRoles() throws Exception {
        mvc.perform(get("/platform/merchant").header("authorization","admin-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(9));
        assertNull(AdminHolder.get());
        mvc.perform(get("/admin/shop").header("authorization","admin-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/private-consumer").header("authorization","admin-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shops);
    }

    @Test
    void platformLoginIsPublicAndLogoutRequiresAdmin() throws Exception {
        when(admins.login(any())).thenReturn(Result.ok("new-admin-token"));
        mvc.perform(post("/platform/admin/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"platform-test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("new-admin-token"));
        mvc.perform(post("/platform/admin/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/platform/admin/logout").header("authorization","admin-token")).andExpect(status().isOk());
        verify(admins).logout("admin-token");
    }

    @Test
    void platformStatusRejectsProtectedFieldsBeforeBusinessService() throws Exception {
        for (String field : new String[]{"id","username","passwordHash","sessionVersion","ownerId"}) {
            mvc.perform(put("/platform/merchant/1/status").header("authorization","admin-token").contentType("application/json")
                    .content("{\"status\":0,\""+field+"\":1}")).andExpect(status().isBadRequest());
        }
        verify(admins,never()).changeMerchantStatus(any(),any());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @Import({WebMvcConfig.class,RefreshTokenInterceptor.class,MerchantLoginInterceptor.class,AdminLoginInterceptor.class,
            AdminController.class,PlatformMerchantController.class,PlatformExceptionAdvice.class,
            MerchantController.class,MerchantExceptionAdvice.class,ShopController.class,VoucherController.class})
    static class Config {
        @Bean com.hmdp.controller.admin.ShopController merchantShopController() { return new com.hmdp.controller.admin.ShopController(); }
        @Bean IMerchantService merchantService() { return mock(IMerchantService.class); }
        @Bean IAdminService adminService() { return mock(IAdminService.class); }
        @Bean IShopService shopService() { return mock(IShopService.class); }
        @Bean IShopSearchService shopSearchService() { return mock(IShopSearchService.class); }
        @Bean IVoucherService voucherService() { return mock(IVoucherService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ConsumerProbeController consumerProbeController() { return new ConsumerProbeController(); }
    }

    /** 必须存在真实 Handler 才会执行 MVC 拦截器，不能用不存在的路径测试登录隔离。 */
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.boot.test.context.TestComponent
    static class ConsumerProbeController {
        @org.springframework.web.bind.annotation.GetMapping("/private-consumer")
        public Result probe() { return Result.ok(); }
    }
}
