package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 门店探店笔记：发布、阅读与点赞，不推送粉丝收件箱。
 * 点赞保留ZSet（member=userId、score=时间戳），用于当前点赞状态与最近点赞者。
 * ZSet与数据库计数仍非跨存储事务，不将该教学实现表述为强一致点赞系统。
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    /**
     * 发布一篇与门店关联的笔记，执行顺序为“校验输入 → 构建白名单实体 → 写MySQL”。
     *
     * <p>Controller虽然接收Blog，但不能直接持久化该对象：客户端可能额外提交id、userId、
     * liked等字段。这里重新new一个Blog，仅复制允许作者填写的内容，身份取UserHolder。
     * 发布入口已经过登录拦截器，游客不能通过传入userId冒充作者。</p>
     *
     * <p>删除原来的“保存后遍历粉丝，逐个ZADD收件箱”步骤。笔记可通过首页、作者页和门店页
     * 查询到；关注只是保存作者关系，不再承担动态分发。评论数保留为0以兼容原表字段。</p>
     */
    @Override
    public Result saveBlog(Blog input) {
        // 长度和必填校验放在服务端，不能只依赖浏览器maxlength或上传按钮的9图限制。
        // images仍是原项目的逗号分隔地址串；这里校验长度/数量，不代表验证了图片所有权或内容。
        if (input == null || StrUtil.isBlank(input.getTitle()) || input.getTitle().length() > 255
                || StrUtil.isBlank(input.getContent()) || input.getContent().length() > 2048
                || StrUtil.isBlank(input.getImages()) || input.getImages().length() > 2048
                || input.getImages().split(",").length > 9) {
            return Result.fail("请填写标题、正文和1至9张照片，标题最多255字、正文最多2048字");
        }
        // 门店必须实际存在，否则“笔记详情 → 查看门店”会成为断链；分类搜索结果不能代替写入校验。
        if (input.getShopId() == null || shopService.getById(input.getShopId()) == null) {
            return Result.fail("请选择有效的关联门店");
        }
        // 不直接save客户端实体，避免伪造作者、已有笔记ID、点赞数和创建时间。
        Blog blog = new Blog().setUserId(UserHolder.getUser().getId()).setShopId(input.getShopId())
                .setTitle(input.getTitle().trim()).setContent(input.getContent().trim())
                .setImages(input.getImages()).setLiked(0).setComments(0);
        return save(blog) ? Result.ok(blog.getId()) : Result.fail("发布笔记失败");
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("探店笔记不存在或已删除！");
        }
        // 2.填充作者信息 + 是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 切换当前用户的点赞状态。ZSet的member是userId，score是点赞时间，不是用户热度。
     *
     * <p>先ZSCORE决定本次走新增还是移除；真正是否调整MySQL计数，要再看ZADD/ZREM返回值。
     * 两个请求可能同时读到“未点赞”，只有成功新增member的请求才能+1。</p>
     *
     * <p>边界：这是切换接口，不是可任意重放的“设置为已点赞”接口。顺序执行两次会点赞后取消。
     * Redis修改与MySQL计数更新也不在同一事务中；若进程在二者之间退出，仍可能需要数据核对。
     * 因而不能把此处的并发分支防护表述为Exactly-once或跨存储强一致。</p>
     */
    @Override
    public Result likeBlog(Long id) {
        // 先检查笔记，避免为不存在的资源创建孤立的点赞Key。
        if (getById(id) == null) {
            return Result.fail("探店笔记不存在或已删除");
        }
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 2.判断当前用户是否已经点赞（ZSet 的 member 是 userId）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.未点赞 → 点赞：ZSet 加入（score=时间戳），数据库 liked + 1
            // 并发请求都读到未点赞时，只有真正新增member的请求增加计数。
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForZSet()
                    .add(key, userId.toString(), System.currentTimeMillis()))) {
                update().setSql("liked = liked + 1").eq("id", id).update();
            }
        } else {
            // 4.已点赞 → 取消点赞：ZSet 移除，数据库 liked - 1
            Long removed = stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            // ZREM返回实际移除数量：另一个请求已移除时为0，不能再次减计数。
            // GREATEST只防止计数减成负数，不负责修复原有Redis/MySQL差异。
            if (removed != null && removed > 0) {
                update().setSql("liked = GREATEST(liked - 1, 0)").eq("id", id).update();
            }
        }
        return Result.ok();
    }

    /** 最近点赞者头像条：先从Redis取得顺序，再到MySQL读取公开资料。 */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1.查询最近5位点赞用户（按点赞时间倒序，不是热度排名）
        Set<String> top5 = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出 userId 列表
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.SQL的IN不保证按传入ID顺序返回，ORDER BY FIELD恢复Redis中的时间顺序。
        // 拼接内容来自上面Long解析后的Redis成员，不直接拼接HTTP传入的SQL片段。
        String idStr = StrUtil.join(",", ids);
        List<User> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 4.转 UserDTO（脱敏）
        List<UserDTO> dtoList = BeanUtil.copyToList(users, UserDTO.class);
        return Result.ok(dtoList);
    }

    /**
     * 本人笔记与公开作者笔记复用同一查询。本人入口由Controller从登录身份取userId；
     * 公开作者入口接受作者ID。稳定次序为create_time DESC、id DESC，每页固定10条。
     * 此处是普通页码分页，不再使用Feed的“时间戳+同分偏移”协议；动态更新时仍可能发生页间漂移。
     */
    @Override
    public Result queryBlogByUserId(Integer current, Long userId) {
        if (userId == null || userId <= 0 || current == null || current < 1 || current > 1000) {
            return Result.fail("用户编号或页码不正确");
        }
        Page<Blog> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time", "id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::isBlogLiked);
        return Result.ok(records);
    }

    /**
     * 为列表/详情补充展示字段：作者昵称、头像，以及“当前阅读者”是否点赞。
     * 方法名沿用旧接口，实际还承担作者信息填充；游客读取正常返回，只把isLike设为false。
     * 这不意味着放开写权限，发布和点赞仍由MVC登录拦截器保护。
     */
    @Override
    public void isBlogLiked(Blog blog) {
        // 1.查询作者信息
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
        // 2.判断当前登录用户是否已经点赞（未登录一律 false）
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            blog.setIsLike(false);
            return;
        }
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, loginUser.getId().toString());
        blog.setIsLike(score != null);
    }
}
