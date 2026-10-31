package com.hmdp.controller.user;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注 / 取关
     * @param id 要关注的用户id
     * @param isFollow true=关注 false=取关
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long id,
                        @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(id, isFollow);
    }

    /**
     * 判断当前用户是否关注了目标用户
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long id) {
        return followService.isFollow(id);
    }

    /**
     * 本人关注的作者列表；浏览器只传页码，Service从登录上下文确定“本人”。
     * 原/common和粉丝Feed入口已经删除，不用返回空数组来假装旧功能仍受支持。
     */
    @GetMapping("/of/me")
    public Result queryFollowing(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return followService.queryFollowing(current);
    }
}
