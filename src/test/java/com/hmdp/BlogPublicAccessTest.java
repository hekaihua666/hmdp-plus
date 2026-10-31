package com.hmdp;

import com.hmdp.controller.user.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 新增公开阅读入口后，验证真实拦截器仍保护发布、点赞和个人笔记，防止放开 /blog/**。 */
class BlogPublicAccessTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private IBlogService blogs;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        blogs = context.getBean(IBlogService.class);
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void anonymousReadersCanReadDetailsAndLikeLists() throws Exception {
        when(blogs.queryBlogById(8L)).thenReturn(Result.ok("公开笔记"));
        when(blogs.queryBlogLikes(8L)).thenReturn(Result.ok(Collections.emptyList()));
        mvc.perform(get("/blog/8")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/blog/likes/8")).andExpect(status().isOk());
    }

    @Test
    void anonymousWritesAndPrivateListsRemainProtected() throws Exception {
        mvc.perform(post("/blog").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/blog/like/8")).andExpect(status().isUnauthorized());
        mvc.perform(get("/blog/of/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(blogs);
    }

    @Test
    void anonymousReadersCanReadAuthorNotes() throws Exception {
        when(blogs.queryBlogByUserId(1, 9L)).thenReturn(Result.ok(Collections.emptyList()));
        mvc.perform(get("/blog/of/user?id=9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(blogs).queryBlogByUserId(1, 9L);
    }

    @Test
    void invalidShopPaginationIsRejectedBeforeDatabaseAccess() throws Exception {
        for (String params : new String[]{"shopId=0", "shopId=1&current=0", "shopId=1&current=1001"}) {
            mvc.perform(get("/blog/of/shop?"+params)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(blogs);
    }

    @org.springframework.boot.test.context.TestConfiguration
    @Import({MerchantAccessTest.Config.class, BlogController.class})
    static class Config {
        @Bean IBlogService blogService() { return mock(IBlogService.class); }
        @Bean IUserService userService() { return mock(IUserService.class); }
    }
}
