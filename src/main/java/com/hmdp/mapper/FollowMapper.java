package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Follow;
import com.hmdp.dto.UserDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {
    /**
     * 从用户表取作者卡片，只选择id、nickName、icon，不把手机号等账号字段加载到返回DTO。
     *
     * EXISTS只问“是否存在关系”：即使旧tb_follow有重复行，每个作者也只返回一次；
     * 直接JOIN会把重复关系展开为重复作者。这里不清洗或删除旧数据。
     * userId由服务端登录身份传入，offset已校验，#{...}交给MyBatis做参数绑定。
     * 按作者ID倒序保证静态列表顺序可重复；这不是按关注时间排序，每页固定10人。
     */
    @Select("SELECT u.id, u.nick_name AS nickName, u.icon FROM tb_user u "
            + "WHERE EXISTS (SELECT 1 FROM tb_follow f WHERE f.user_id=#{userId} "
            + "AND f.follow_user_id=u.id) ORDER BY u.id DESC LIMIT #{offset},10")
    List<UserDTO> selectFollowing(@Param("userId") Long userId, @Param("offset") int offset);
}
