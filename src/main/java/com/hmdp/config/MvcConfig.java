package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 刷新Token的拦截器，优先级设为 0
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);

        // 2. 登录校验的拦截器，优先级设为 1
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login")
                .order(1);
    }

    /*
     * 这是一个非常经典的问题。技术上确实可以把所有逻辑写在一个拦截器里，但在黑马点评这类实际业务场景中，拆分成两个拦截器（RefreshToken 和
     * Login）是极其精妙的设计。
     * 
     * 主要原因有以下三点：
     * 
     * 1. 解决“Token 续期”的死角问题
     * 如果只用一个 LoginInterceptor 并只拦截需要登录的路径（比如 /order）：
     * 
     * 问题： 用户如果一直在浏览“不需要登录”的页面（比如首页 /shop、详情页），这些页面不会触发拦截器。
     * 
     * 后果： 用户的 Token 在 Redis 中会因为没有被刷新而过期。即使用户一直在操作，只要没去点“我的”或“下单”，30 分钟后就会被强制踢下线。
     * 
     * 拆分后的逻辑：
     * 
     * RefreshTokenInterceptor： 拦截 /**（所有路径）。只要用户在点，哪怕是看新闻，我们都在后台悄悄帮他刷新 Redis 有效期。
     * 
     * LoginInterceptor： 只拦截需要权限的路径。
     */
}
