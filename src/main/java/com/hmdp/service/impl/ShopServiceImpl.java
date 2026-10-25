package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
public class ShopServiceImpl
        extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override

    public Result queryById(Long id) {

        String key = "shop:" + id;
        // 1. 从 Redis 缓存中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 3. 【核心防穿透逻辑】判断缓存中是否为“空对象”
        // 虽然它通过了 isNotBlank 的筛选（说明它不是有效数据），但它居然还不是 null，那它肯定就是那个坏蛋（空字符串 ""）
        if (shopJson != null) {
            return Result.fail("店铺不存在！");
        }
        // 4. 缓存未命中，查询数据库
        Shop shop = getById(id);

        // 5. 数据库查询为空 (处理缓存穿透)
        if (shop == null) {
            // 【关键】将空值写入 Redis，并设置较短的过期时间 (TTL)
            // 目的：防止恶意用户利用不存在的 ID (如 id=-1) 频繁冲击数据库
            stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
            return Result.fail("店铺不存在！");
        }
        // 6. 数据库存在，写入 Redis 并设置过期时间

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        return Result.ok(shop);

    }

    // 实现缓存击穿方案：互斥锁
    @Override
    public Result MutexLockqueryById(Long id) {
        String key = "shop:" + id;
        String lockKey = "lock:shop:" + id; // 锁的Key

        // 1. 从 Redis 缓存中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 3. 判断是否是之前缓存的空对象 (解决穿透)
        if (shopJson != null) {// 说明shopJson=空字符串
            return Result.fail("店铺不存在！");
        }

        // 4. 实现缓存击穿方案：互斥锁
        Shop shop = null;
        try {
            // 4.1 尝试获取互斥锁
            boolean isLock = tryLock(lockKey);

            // 4.2 判断获取锁是否成功
            if (!isLock) {
                // 失败，则休眠并重试（自旋）
                Thread.sleep(50);
                return queryById(id); // 递归重试
            }

            // 4.3 获取锁成功，【二次检查】Double Check
            // 拿到锁后应再次查缓存，防止在等待锁期间别人已经更新了缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
            }

            // 5. 查询数据库
            shop = getById(id);

            // 6. 数据库不存在 (处理穿透)
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
                return Result.fail("店铺不存在！");
            }

            // 7. 数据库存在，写入 Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8. 释放锁 (一定要放在 finally 块)
            unlock(lockKey);
        }

        return Result.ok(shop);
    }

    // 辅助方法：获取锁
    private boolean tryLock(String key) {
        // 使用 SETNX 设置锁，过期时间建议设置短一点，防止死锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 辅助方法：释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional // 保证数据库更新的事务性
    public void updateShop(Shop shop) {
        // TODO: 更新数据库
        updateById(shop);
        // 删除缓存（cache aside 策略）
        stringRedisTemplate.delete("shop:" + shop.getId());
    }
}
