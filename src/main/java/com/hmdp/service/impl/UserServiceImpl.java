package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2.发送验证码
        String code = RandomUtil.randomNumbers(6);
        // 3，保存验证码到 redis
        stringRedisTemplate.opsForValue().set(phone, code, 10, TimeUnit.MINUTES);
        log.info("发送验证码成功，验证码是：{}", code);
        return Result.ok();
    }

    /// 当他填写好验证码，点击登陆时使用
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        // 2.校验验证码
        String code = loginForm.getCode();
        String sessionCode = stringRedisTemplate.opsForValue().get(loginForm.getPhone());
        if (!code.equals(sessionCode)) {
            return Result.fail("验证码错误");
        }
        // 3.查询用户
        // 获取 LambdaQueryWrapper 查询对象
        // 使用 User::getPhone 代替字符串 "phone"
        User user = lambdaQuery()
                .eq(User::getPhone, loginForm.getPhone())
                .one();
        if (user == null) {
            // 4.如果不存在，创建新用户并保存
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(loginForm.getPhone());
            save(user);
        }

        // 7.1. 随机生成 token，作为登录令牌
        String token = UUID.randomUUID().toString(true); // 用 Hutool 的 toString(true) 生成不带连字符的 UUID 更简洁

        // 7.2. 将 User 对象转为 UserDTO (脱敏)
        UserDTO userDTO = BeanUtil.toBean(user, UserDTO.class);

        // 7.3. 将 UserDTO 转为 Map
        // 注意：必须把 Long 类型的 ID 转成 String，否则 StringRedisTemplate 会报错
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); // 这里是关键：转 String

        // 7.4. 存储到 Redis
        String tokenKey = LOGIN_USER_KEY + token; // 建议加上业务前缀
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 7.5. 设置有效期 (非常重要！Session 必须有过期时间)
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8. 返回 token 给前端
        return Result.ok(token);

    }
}
