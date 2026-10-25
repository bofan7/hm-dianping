package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 该拦截器的核心职责：
 * 1. 拦截 /** (全路径)，实现“悄悄”续期。
 * 2. 解决 HTTP 无状态问题：通过每笔请求重新获取 Token 并还原用户信息到 ThreadLocal。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 1. 获取请求头中的 token
        String token = request.getHeader("authorization");

        /*
         * 【HTTP 无状态性说明】
         * 由于 HTTP 协议本身不记录用户信息，服务器唯一的凭证就是 Token。
         * 如果没有 Token，说明是匿名访问或初次访问。
         */
        if (StrUtil.isBlank(token)) {
            /*
             * 【为什么不返回 401？】
             * 本拦截器拦截 /**（全路径）。如果在此拦截，用户连“首页”和“登录页”都进不去。
             * 它的任务是：有 Token 就刷，没 Token 就放行。
             * “拦截守卫”的职责交由后续执行顺序更靠后的 LoginInterceptor 处理。
             */
            return true;
        }

        // 2. 基于 TOKEN 获取 redis 中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3. 判断用户是否存在 (登录态是否过期)
        if (userMap.isEmpty()) {
            // Redis 里没数据，说明登录已失效。同样放行，由后续 LoginInterceptor 拦截。
            return true;
        }

        /*
         * 【为什么每次请求都要重新存入 UserHolder？】
         * 1. 隔离性：UserHolder 底层是 ThreadLocal，数据绑定在“当前线程”。
         * 2. 新请求 = 新开始：即使是同一个用户，每发起一次 HTTP 请求，都是一个全新的生命周期。
         * 为了保证 Controller 层能随时取到人，必须在请求入口处重新还原用户信息。
         */
        // 5. 将查询到的 hash 数据转为 UserDTO 对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 6. 保存用户信息到当前线程的 ThreadLocal 中
        UserHolder.saveUser(userDTO);

        // 7. 刷新 token 有效期 (TTL)
        // 只要用户还在点击（无论访问什么页面），我们就在后台帮他悄悄续命，防止掉线。
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        /*
         * 【线程复用与内存泄漏防范】
         * 1. Tomcat 使用线程池，请求结束后线程会被回收而非销毁。
         * 2. 如果不移除，该线程下次处理别人请求时，可能会导致“身份串串香”（乙看到了甲的数据）。
         * 3. 及时 removeUser 也是为了防止 ThreadLocal 导致的内存泄漏 (OOM)。
         */
        UserHolder.removeUser();
    }
}