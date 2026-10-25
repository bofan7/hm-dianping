# 黑马点评 / hm-dianping

个人 Redis 实战项目，包含 Spring Boot 后端、静态前端、示例图片、SQL、Lua 秒杀脚本和运行配置。

## 依赖

- JDK 8 或 11、Maven 3.9+（Spring Boot 2.3.12）
- MySQL 8.0、Redis 7.2、Nginx；推荐 Docker Compose 启动这些服务。
- 安装包从 [Adoptium](https://adoptium.net/)、[Maven](https://maven.apache.org/)、[Docker](https://www.docker.com/products/docker-desktop/) 获取，仓库不收录软件二进制和虚拟机镜像。

## 本地运行（PowerShell）

```powershell
Copy-Item .env.example .env
# 编辑 .env：设置 DB_PASSWORD 和 REDIS_PASSWORD
docker compose up -d
# 等待数据库初始化完成，创建秒杀订单消费组：
./runtime/redis/init-stream.ps1
$env:DB_PASSWORD = '<与 .env 一致的数据库密码>'
$env:REDIS_PASSWORD = '<与 .env 一致的Redis密码>'
mvn -DskipTests package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

在仓库根目录启动后端，打开 <http://localhost:8080>；Nginx 将 `/api/` 转发到宿主机的 8081 端口。`.env` 仅供 Compose 读取，本机 Java 进程需要上面的环境变量。

首次启动自动导入 `src/main/resources/db/hmdp.sql`；已存在 MySQL 数据卷不会重新执行初始化脚本。消费组已存在时出现 `BUSYGROUP` 可直接继续。

外部服务地址可通过 `DB_URL`、`DB_USERNAME`、`REDIS_HOST`、`REDIS_PORT` 覆盖。Redisson 与 Spring Redis 使用同一组配置。图片默认写入 `frontend/imgs/`，在其他目录启动时设置 `IMAGE_UPLOAD_DIR` 为该文件夹的绝对路径（末尾包含路径分隔符）。

课程登录验证码在后端日志中查看。MySQL 示例数据不会自动生成 Redis GEO、缓存或秒杀库存；相关接口测试需按业务写入对应 Redis 数据。秒杀前通过新增秒杀券接口创建活动及其库存。

## 提取与兼容性调整

- 源码来自个人 `hm-dianping`；前端从课程 Nginx 包中提取，保留必需 JS/CSS/字体/示例图片。
- 保留 SQL 与 `seckill.lua`，排除安装包、嵌套 Git 历史、日志、target、PPT 和讲义。
- 移除固定虚拟机 IP、密码和本机绝对路径。
- Springdoc 改用与 Boot 2 兼容的 `springdoc-openapi-ui:1.7.0`；Redisson 使用核心库，避免 starter 的 Spring Data 版本冲突。

原有测试包含数据库/Redis 集成操作；打包跳过测试，不代表完整联调通过。
