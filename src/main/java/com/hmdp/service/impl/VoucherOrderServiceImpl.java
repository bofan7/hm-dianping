package com.hmdp.service.impl;

import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import java.time.Duration;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdGenerator redisIdGenerator;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    // 加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // seckillVoucher函数是主流程,只和redis交互，保证高并发下的性能，类似于write behind的缓存策略
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 订单id提前生成，以便放入 Lua
        long orderId = redisIdGenerator.nextId("order");

        // 1.执行lua脚本，逻辑：库存校验、一人一单校验、发送到Stream
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 返回订单id给前端
        return Result.ok(orderId);
    }

    /*
     * 从这这开始到最后就是异步处理了，和主流程没关系了，用来把redis中的订单信息保存到数据库中
     */
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息。对应命令：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(

                            /*
                             * g1是一个group的名字，之所以区分多个group是因为每一条消息需要被不同的业务分别用一次
                             * 一个group内会维护一个书签，确保重启后能从上次处理的消息继续，不同group之间进度独立
                             * 一个group内还会维护一个PEL（Pending-List），记录已经读了但还没ACK的消息，防止读过的消息
                             * 执行中途失败导致消息丢失
                             * 
                             * c1是一个consumer的名字，相当于一台服务器来处理任务
                             */
                            Consumer.from("g1", "c1"),

                            // 【COUNT 1】：一次只领一个任务，避免贪多嚼不烂导致处理超时
                            // 【BLOCK 2s】：没活就在窗口等2秒，有新订单立刻端走，比死循环轮询省CPU
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),

                            // 【ReadOffset.lastConsumed()】：对应 ">" 符号
                            // 只读“新菜”：即从未分配给组内任何人的新消息。
                            // 注意：那些领了没ACK的消息（欠账）不在这里，在PEL里，需用 ReadOffset.from("0") 找回
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));

                    // 2. 判空：若2秒内没新订单，继续守株待兔
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    // 3. 解析并处理业务逻辑（下单入库）
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);

                    // 4. 【确认平账】对应命令：XACK s1 g1 [ID]
                    // 作用：从当前group的PEL（待处理列表）中移除，代表“这笔账算清了”。
                    // 提醒：ACK不会物理删除Stream里的消息，物理删除通常靠 Lua 脚本中的 MAXLEN 定长修剪。
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 【异常处理】：如果代码崩了没ACK，消息会卡在 g1 的欠账单(PEL)里。
                    // 下一步：调用 handlePendingList()，用 "0" 偏移量去重读这些“领了没做的活”。
                    handlePendingList();
                }
            }
        }

        /**
         * 兜底方案：处理之前读取了但未 ACK 的消息
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取 Pending-List 中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            // 【ReadOffset.from("0")】：对应 "0" 符号
                            // 只读“欠账单”：即PEL（Pending-List）中的所有消息。
                            // 作用：用于处理断点续传，把之前没处理完的订单补上。
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    if (list == null || list.isEmpty()) {
                        // Pending-List 为空，说明没有异常消息，退出
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 2. 重新处理订单
                    handleVoucherOrder(voucherOrder);
                    // 3. 重新 ACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理 Pending-List 异常", e);
                    // 休息一下继续循环
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 虽然之前我们手写的lua脚本来做库存校验和一人一单校验，
     * 但还是有极小的概率会出现问题，所以这里需要再次进行校验
     * 于是用：分布式锁（redisson）兜底+数据库事务
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 这个trylock内置了lua脚本，用来做看门狗机制
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 悲观锁的范围必须大于事务，否则事务提交了锁还没释放，导致其他线程进不来
            // 这里用代理对象来调用事务方法，因为只有代理对象调用事务方法，事务才会生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }

        // 7.创建订单
        save(voucherOrder);
    }
}