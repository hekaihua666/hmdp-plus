package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;

/**
 * 轻关注的唯一关系源是MySQL的tb_follow。
 * 删除Redis Set双写、共同关注和粉丝Feed后，读写都回到这一份关系，减少一致性维护成本。
 * 这里关注的是“作者账号”，不是商户账号或店铺；关注后可从作者列表进入公开笔记页。
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private IUserService userService;

    /**
     * 请求表达目标状态：true表示确保关注，false表示确保取关；不是反复调用就反转的toggle。
     *
     * <p>不能只“count==0再insert”：两次并发首次关注可能都查到0。选择锁当前用户的主键行，
     * 让同一账号的关注修改串行执行。已有用户行一定可锁，而尚不存在的关注关系没有现成行可锁。
     * 锁在MySQL中，多个应用实例也遵守；不同关注者不共用这一把用户行锁。</p>
     *
     * <p>关键顺序：先执行SELECT ... FOR UPDATE，再做任何普通SELECT。本机使用REPEATABLE READ，
     * 普通SELECT会建立事务快照；若先查作者再等待用户锁，后面的count可能继续看到旧快照，
     * 锁虽然串行了执行，却仍可能重复插入。当前HTTP入口通过Spring代理开启本方法事务，
     * 锁前没有数据库快照读；若未来复用到已有快照的外层事务，必须重新审视这一前提。</p>
     *
     * <p>@Transactional覆盖取锁、检查与写入，提交/回滚后释放锁。若没有事务，查询结束就释放锁，
     * 无法保护后续count和save。这是当前服务写路径的约束，不是数据库唯一索引，绕过服务直接写表
     * 的程序不受它保护。</p>
     */
    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null || followUserId <= 0 || userId.equals(followUserId)) {
            return Result.fail("不能关注自己或无效用户");
        }
        // 登录身份来自Redis会话，不必先查数据库；把本方法的首次数据库访问留给用户行当前读。
        User current = userService.query().eq("id", userId).last("FOR UPDATE").one();
        if (current == null) {
            return Result.fail("当前用户不存在，请重新登录");
        }
        // 必须在锁之后查询作者，避免REPEATABLE READ在等待锁之前就建立旧Read View。
        if (userService.getById(followUserId) == null) {
            return Result.fail("作者不存在");
        }
        // 两个条件必须同时存在：只修改“当前用户 → 指定作者”，不能按作者ID删掉所有人的关注。
        LambdaQueryWrapper<Follow> relation = new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
        if (Boolean.TRUE.equals(isFollow)) {
            // 持有用户锁时再查关系；前一请求已经提交的关注可以被当前新建快照读到。
            if (count(relation) == 0) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                save(follow);
            }
        } else {
            // 兼容历史重复关系，取关时移除本人与该作者的所有记录。
            remove(relation);
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 判定来源与写入来源一致，不再依赖可能缺失或未同步的Redis关注Set。
        long count = count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, UserHolder.getUser().getId())
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    /**
     * 返回“本人关注的作者”，不是全站作者，也不是这些作者最新发布的Feed。
     * userId只从UserHolder取，不接受客户端代传；校验后把页码换算成SQL offset。
     * 每页10人须与profile.js的结束判定保持一致，EXISTS兼容历史重复关系。
     */
    @Override
    public Result queryFollowing(Integer current) {
        if (current == null || current < 1 || current > 1000) {
            return Result.fail("页码不正确");
        }
        return Result.ok(baseMapper.selectFollowing(UserHolder.getUser().getId(), (current - 1) * 10));
    }
}
