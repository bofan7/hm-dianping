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

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        /*
         * 该拦截器的核心职责：
         * 1.
         * 拦截敏感路径，如果用户已经登陆，说明refreshTokenInterceptor已经把他放入了ThreadLocal(UserHolder),则放行。
         * 2. 如果没有用户，说明未登录，拦截并设置状态码为 401 (Unauthorized)
         */
        // 1. 从 ThreadLocal 中获取用户信息 (UserHolder 是之前步骤存放数据的工具类)
        if (UserHolder.getUser() == null) {
            // 2. 如果没有用户，说明未登录，拦截并设置状态码为 401 (Unauthorized)
            response.setStatus(401);
            // 返回 false，拦截请求
            return false;
        }

        // 3. 有用户，则放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        // 移除用户，防止内存泄漏（这步也可以放在 RefreshTokenInterceptor 的最后）
        UserHolder.removeUser();
    }

}
