package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    // 用redis缓存
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource // 类似spring的@Autowired
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        String key = "shopType:top";
        // 1. 从缓存查询 (获取整个 List 的 JSON 字符串)
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否命中
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 命中：直接把字符串转回 List 对象并返回
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }

        // 3. 缓存未命中，查数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();

        // 判空处理
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("分类不存在！");
        }

        // 4. 写入缓存 (将整个 List 转成 JSON 字符串存入)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        // 建议养成好习惯，给缓存加个有效期（例如 30 分钟）
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);

        return Result.ok(typeList);
    }
}
