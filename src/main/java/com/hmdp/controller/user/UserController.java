package com.hmdp.controller.user;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     */
    @PostMapping("/code")
    public Result sendCode(String phone){
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        //2.生成6位验证码
        String code = RandomUtil.randomNumbers(6);
        //3保存验证码到redis String
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //模拟发送短信，控制台打印
        System.out.println("验证码："+code);
        return Result.ok();
    }

    /**
     * 短信登录
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //1校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2校验验证码，从redis拿
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3 根据手机号查用户，没有就创建
        User user = userService.query().eq("phone", phone).one();
        if(user == null){
            user = userService.createUserWithPhone(phone);
        }
        //4 生成token UUID
        String token = UUID.randomUUID().toString(true);
        //5 user转UserDTO脱敏
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //6 UserDTO转map，存入Redis Hash，value全部转为String
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7 设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token给前端
        return Result.ok(token);
    }

    /**
     * 获取当前登录用户信息（脱敏）告诉前端我是谁
     */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 根据id查询用户基本信息（昵称、头像）
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 公开作者入口不能直接返回User实体；UserDTO只含ID、昵称、头像，不包含手机号。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 作者主页的公开简介；只返回城市和介绍，不暴露生日、积分等个人字段。
     */
    @GetMapping("/info/{id}")
    public Result queryUserInfo(@PathVariable("id") Long id) {
        UserInfo userInfo = userInfoService.getById(id);
        if (userInfo == null) {
            return Result.ok();
        }
        // UserInfo还有birthday、credits、level等字段，不能因为主页公开就整对象序列化。
        // 显式选择3个字段，后续实体新增字段时也不会自动对游客暴露。
        Map<String, Object> profile = new HashMap<>();
        profile.put("userId", id);
        profile.put("city", userInfo.getCity());
        profile.put("introduce", userInfo.getIntroduce());
        return Result.ok(profile);
    }

    /**
     * 撤销本次请求携带的消费者会话，不是清空该用户全部会话。
     * 仅删除login:token:命名空间，不影响商户/平台身份；删除已不存在的Key仍可正常完成。
     * 浏览器还需清本地token；若Redis请求失败，前端本地退出不等于服务端已完成撤销。
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        if (token != null) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }
}
