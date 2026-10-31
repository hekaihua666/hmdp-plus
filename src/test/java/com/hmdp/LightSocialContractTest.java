package com.hmdp;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.controller.user.UserController;
import com.hmdp.service.IUserInfoService;
import java.time.LocalDate;
import java.util.Map;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.BlogServiceImpl;
import com.hmdp.service.impl.FollowServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 轻社交边界测试；真实SQL锁、事务及浏览器流程另做本机联调，不用Mock冒充。 */
class LightSocialContractTest {
    private BlogServiceImpl blogs;
    private FollowServiceImpl follows;
    private IShopService shops;
    private IUserService users;
    private StringRedisTemplate redis;
    private FollowMapper mapper;

    @BeforeEach
    void setup() {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        blogs = spy(new BlogServiceImpl()); follows = spy(new FollowServiceImpl());
        shops = mock(IShopService.class); users = mock(IUserService.class);
        redis = mock(StringRedisTemplate.class); mapper = mock(FollowMapper.class);
        ReflectionTestUtils.setField(blogs, "shopService", shops);
        ReflectionTestUtils.setField(blogs, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(follows, "userService", users);
        ReflectionTestUtils.setField(follows, "baseMapper", mapper);
    }

    @AfterEach void clear() { UserHolder.removeUser(); }

    @Test
    void publishRejectsMissingContentOrShopBeforeWriting() {
        assertFalse(blogs.saveBlog(new Blog()).getSuccess());
        assertFalse(blogs.saveBlog(validBlog().setShopId(999L)).getSuccess());
        verify(blogs, never()).save(any()); verifyNoInteractions(redis);
    }

    @Test
    void publishUsesAuthenticatedAuthorAndWhitelistedFieldsWithoutFanout() {
        when(shops.getById(1L)).thenReturn(new Shop());
        doReturn(true).when(blogs).save(any(Blog.class));
        assertTrue(blogs.saveBlog(validBlog().setId(800L).setUserId(900L).setLiked(999).setComments(99)).getSuccess());
        ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);
        verify(blogs).save(saved.capture());
        assertEquals(7L, saved.getValue().getUserId()); assertNull(saved.getValue().getId());
        assertEquals(0, saved.getValue().getLiked()); assertEquals(0, saved.getValue().getComments());
        verifyNoInteractions(redis);
    }

    @Test
    void missingBlogCannotCreateOrToggleLikeKey() {
        doReturn(null).when(blogs).getById(99L);
        assertFalse(blogs.likeBlog(99L).getSuccess()); verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentAlreadyAddedLikeDoesNotIncrementAgain() {
        ZSetOperations<String,String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        doReturn(validBlog()).when(blogs).getById(1L);
        when(zset.score("blog:liked:1", "7")).thenReturn(null);
        when(zset.add(eq("blog:liked:1"), eq("7"), anyDouble())).thenReturn(false);
        assertTrue(blogs.likeBlog(1L).getSuccess()); verify(blogs, never()).update();
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentAlreadyRemovedLikeDoesNotDecrementAgain() {
        ZSetOperations<String,String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        doReturn(validBlog()).when(blogs).getById(1L);
        when(zset.score("blog:liked:1", "7")).thenReturn(1D);
        when(zset.remove("blog:liked:1", "7")).thenReturn(0L);
        assertTrue(blogs.likeBlog(1L).getSuccess()); verify(blogs, never()).update();
    }

    @Test
    void authorPagesRejectInvalidPagination() {
        assertFalse(blogs.queryBlogByUserId(0, 1L).getSuccess());
        assertFalse(blogs.queryBlogByUserId(1001, 1L).getSuccess());
        assertFalse(blogs.queryBlogByUserId(1, -1L).getSuccess());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cannotFollowSelfOrMissingAuthor() {
        // 作者存在性校验已经移到用户锁之后；缺失作者也须先经过当前用户的锁定查询。
        QueryChainWrapper<User> query = mock(QueryChainWrapper.class, RETURNS_SELF);
        when(users.query()).thenReturn(query); doReturn(new User()).when(query).one();
        assertFalse(follows.follow(7L, true).getSuccess());
        assertFalse(follows.follow(999L, true).getSuccess());
        verifyNoInteractions(mapper);
    }

    @Test
    void followingListIsBoundToCurrentUserAndRejectsInvalidPages() {
        assertFalse(follows.queryFollowing(0).getSuccess());
        assertFalse(follows.queryFollowing(1001).getSuccess()); verifyNoInteractions(mapper);
        when(mapper.selectFollowing(7L, 10)).thenReturn(Collections.emptyList());
        assertTrue(follows.queryFollowing(2).getSuccess()); verify(mapper).selectFollowing(7L, 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedFollowDoesNotInsertAnotherRelationship() {
        when(users.getById(8L)).thenReturn(new User());
        QueryChainWrapper<User> query = mock(QueryChainWrapper.class, RETURNS_SELF);
        when(users.query()).thenReturn(query); doReturn(new User()).when(query).one();
        doReturn(1L).when(follows).count(any());
        assertTrue(follows.follow(8L, true).getSuccess());
        verify(follows, never()).save(any(Follow.class)); verify(query).last("FOR UPDATE");
    }

    /**
     * 顺序本身是并发保证的一部分：先普通SELECT会让RR事务提前持有旧快照。
     * 这里验证调用顺序；真正的InnoDB等待锁/快照行为另由六请求锁屏障联调验证。
     */
    @Test
    @SuppressWarnings("unchecked")
    void lockUserBeforeTheFirstConsistentRead() {
        QueryChainWrapper<User> query = mock(QueryChainWrapper.class, RETURNS_SELF);
        when(users.query()).thenReturn(query); doReturn(new User()).when(query).one();
        when(users.getById(8L)).thenReturn(new User());
        doReturn(1L).when(follows).count(any());
        assertTrue(follows.follow(8L, true).getSuccess());
        org.mockito.InOrder order = inOrder(query, users, follows);
        order.verify(query).one();
        order.verify(users).getById(8L);
        order.verify(follows).count(any());
    }

    @Test
    void publicAuthorProfileDoesNotExposeBirthdayOrCredits() {
        IUserInfoService infos = mock(IUserInfoService.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userInfoService", infos);
        when(infos.getById(7L)).thenReturn(new UserInfo().setUserId(7L).setCity("杭州")
                .setIntroduce("喜欢探店").setBirthday(LocalDate.of(2000, 1, 1)).setCredits(100));
        Object result = controller.queryUserInfo(7L).getData();
        assertTrue(result instanceof Map);
        Map<?, ?> profile = (Map<?, ?>) result;
        assertEquals(3, profile.size()); assertEquals("喜欢探店", profile.get("introduce"));
        assertFalse(profile.containsKey("birthday")); assertFalse(profile.containsKey("credits"));
    }

    private Blog validBlog() {
        return new Blog().setShopId(1L).setTitle("探店记录").setContent("分享真实体验")
                .setImages("/imgs/demo/photos/test.jpg");
    }
}
