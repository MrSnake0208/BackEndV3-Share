# BackEndV3-Share

基于 MaaYuan-Share-Backend 架构模板搭建的后端框架骨架。

## 技术栈

- Kotlin 2.2 (JDK 21)
- Spring Boot 3.5
  - spring-security (JWT 无状态认证)
  - springdoc-openapi
- MongoDB (Spring Data)
- Redis
- Gradle (Kotlin DSL) + ktlint + MockK

## 本地开发指南

本地 JVM 直接运行在 WSL/Nix 环境中，Docker Compose 只启动 MongoDB 和 Redis：

```bash
./scripts/dev-up.sh
nix develop path:.
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`scripts/dev-up.sh` 会启动单节点 MongoDB replica set `rs0` 和 Redis，初始化一次 replica set，等待 PRIMARY，
并验证 replica set discovery 与 Redis `PING`。本地 profile 固定使用：

- 后端：`http://127.0.0.1:8080`
- MaaBackend：`mongodb://127.0.0.1:27017/MaaBackend?replicaSet=rs0`
- HubBackend：`mongodb://127.0.0.1:27017/HubBackend?replicaSet=rs0`
- Redis：`127.0.0.1:6379`

库存导入使用 MongoDB transaction，因此 MongoDB 必须以 replica set 运行；standalone MongoDB 不受支持。
启动后检查：

```bash
curl --fail-with-body http://127.0.0.1:8080/ready | jq
curl --fail-with-body http://127.0.0.1:8080/v3/api-docs \
  | jq '.servers'
```

第二条命令在 local profile 下应显示 `http://127.0.0.1:8080`。

MongoDB 与 Redis 使用命名 volume。普通停止不会删除本地数据：

```bash
docker compose -f compose.dev.yml down
```

需要显式重置测试数据时可执行下面的命令。**该命令会永久删除本仓库 Compose 环境的全部本地 MongoDB 和 Redis 数据：**

```bash
docker compose -f compose.dev.yml down -v
```

Windows 上的 MaaY 通常可直接用 `http://127.0.0.1:8080` 访问 WSL 后端。如果 WSL localhost 转发不可用，
在 WSL 中运行 `hostname -I` 查询当前 IP，并在 MaaY 中临时填写 `http://<WSL_IP>:8080`；不要把动态 IP 写入配置文件。

### 创建本地账号和 API Token

local profile 已开启 `debug.email.no-send: true`，不会连接 SMTP。先请求注册验证码：

```bash
export API_BASE_URL=http://127.0.0.1:8080
export LOCAL_EMAIL=developer@example.test
export LOCAL_PASSWORD='replace-with-a-local-password'

curl --fail-with-body -X POST "$API_BASE_URL/user/sendRegistrationToken" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LOCAL_EMAIL\"}" | jq
```

验证码会出现在运行 `bootRun` 的终端以及 `logs/latest.log` 中，日志文本为
`Email not sent, no-send enabled, vcode is ...`。把验证码仅放在当前终端后注册并登录：

```bash
export REGISTRATION_CODE='replace-with-code-from-log'

curl --fail-with-body -X POST "$API_BASE_URL/user/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LOCAL_EMAIL\",\"user_name\":\"localdev\",\"password\":\"$LOCAL_PASSWORD\",\"registration_token\":\"$REGISTRATION_CODE\"}" \
  | jq

export JWT_TOKEN="$(curl --fail-with-body -sS -X POST "$API_BASE_URL/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LOCAL_EMAIL\",\"password\":\"$LOCAL_PASSWORD\"}" \
  | jq -er '.data.token')"
```

先创建库存子账号，再使用 JWT 为该账号生成同时包含读、写、导出 scope 的本地 API Token：

```bash
export INVENTORY_ACCOUNT_ID="$(curl --fail-with-body -sS -X POST "$API_BASE_URL/v1/inventory/accounts" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"本地烟测账号"}' \
  | jq -er '.data.id')"

export INVENTORY_API_TOKEN="$(curl --fail-with-body -sS -X POST "$API_BASE_URL/user/open-api/token" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"account_id\":\"$INVENTORY_ACCOUNT_ID\",\"scopes\":[\"inventory:read\",\"inventory:write\",\"inventory:export\"],\"remark\":\"local inventory smoke\"}" \
  | jq -er '.data.token')"
```

JWT 和完整 API Token 只保存在当前终端环境变量中，不要写入文件或命令日志。可用以下命令确认 scope：

```bash
curl --fail-with-body "$API_BASE_URL/user/open-api/tokens" \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.data[] | {token_id, account_id, account_name, scopes, remark}'
```

### 库存联调 Smoke Test

另开一个已进入 `nix develop path:.` 的终端，将刚生成的完整 API Token 放入环境变量后运行：

```bash
export INVENTORY_API_TOKEN='replace-with-local-api-token'
./scripts/inventory-smoke.sh
```

脚本默认访问 `http://127.0.0.1:8080`，也可通过 `API_BASE_URL` 覆盖。它从 Token 自动查询绑定的
库存子账号，不要求另外配置 `account_id`。脚本从真实目录选择 item，使用唯一
`record_id` 验证首次导入、幂等重传、409 冲突、当前库存、原始交换文档导出和直接回传导入；脚本不会打印 Token。

## 项目结构

沿用 MaaYuan-Share-Backend 的分层:

```
src/main/kotlin/com/lhs/share/
├── ShareApplication.kt        # 应用入口
├── common/                    # 共享的逻辑
│   ├── controller/            #   PagedDTO、Page 转 PagedDTO 扩展
│   └── utils/                 #   IpUtil 等通用工具
├── config/                    # Spring 配置
│   ├── accesslimit/           #   @AccessLimit 接口限流(注解+拦截器)
│   ├── doc/                   #   OpenAPI 文档配置、@RequireJwt 文档注解
│   ├── external/              #   @ConfigurationProperties 配置类(share.*)
│   └── security/              #   Spring Security + JWT 过滤器链
├── controller/                # 交互层
│   ├── request/               #   入参类型
│   └── response/              #   响应类型(ApiResult 统一返回)
├── handler/                   # 全局异常处理
├── repository/                # 数据仓库层,用于和数据库交互
│   └── entity/                #   与数据库字段对应的类型
└── service/                   # 业务处理层,复杂或者公用逻辑放在这里
    └── jwt/                   #   JWT 签发/解析
```

## 如何新增一个接口

参照已有的 `Demo` 示例(controller → service → repository → entity),
按以下步骤编写:

1. 在 `repository/entity` 下新建实体类(标注 `@Document` 对应 MongoDB 集合);
2. 在 `repository` 下新建仓储接口,继承 `MongoRepository<实体, 主键类型>`;
3. 在 `service` 下新建服务类,注入仓储,实现业务逻辑;
4. 在 `controller/request` 下新建入参 DTO(可用 Bean Validation 注解校验);
5. 在 `controller/response` 下新建响应 DTO;
6. 在 `controller` 下新建 Controller,方法返回 `ApiResult<T>`。

接口权限:

- 公开接口:加入 `SecurityConfig` 的 `URL_PERMIT_ALL` 白名单;
- 需要登录的接口:默认即为 authenticated,无需额外配置,
  可加 `@RequireJwt` 注解让 Swagger 文档展示认证要求;
- 精细权限:参考原项目在 `SecurityConfig` 中按 authority 放行,
  权限写入 JWT 的 authorities claim(`JwtService.issueAuthToken` 的第三个参数)。

其他约定:

- 接口需要限流时,在方法上加 `@AccessLimit(times = 5, second = 10)`;
- 需要缓存时,用 `@Cacheable` / `@CacheEvict`(Caffeine 进程内缓存)或注入 RedisCache;
- 定时任务放在 `task` 包,标注 `@Scheduled`(已开启 `@EnableScheduling`);
- 异步任务标注 `@Async`(已开启 `@EnableAsync`)。

## 账号模块

与 MaaYuan-Share-Backend 共用同一个 MongoDB 数据库(`MaaBackend`,连接串 `mongodb://192.168.31.21:27017/MaaBackend`),
注册/登录/改密/邮箱验证码/JWT 全部在本服务内实现,直接读写 `maa_user` 集合,
业务语义与原项目保持一致(字段结构、密码 BCrypt、status 状态、权限 authority 均对齐)。

### 接口清单

| 接口 | 说明 | 认证 |
|---|---|---|
| POST /user/register | 用户注册(邮箱验证码) | 匿名 |
| POST /user/sendRegistrationToken | 发送注册验证码 | 匿名 |
| POST /user/login | 登录,返回 access+refresh token | 匿名 |
| POST /user/refresh | 刷新 token | 匿名(无状态校验) |
| POST /user/update/password | 修改密码(原密码,10 分钟频率限制) | 需登录 |
| POST /user/update/info | 修改用户名 | 需登录 |
| POST /user/password/reset_request | 发送重置密码验证码 | 公开 |
| POST /user/password/reset | 邮箱验证码重置密码 | 公开 |
| GET /user/info?userId= | 查询用户公开信息(404 语义) | 公开 |
| GET /user/search?userName=&page=&size= | 用户模糊搜索(size 上限 50) | 公开 |

### 字段命名约定

全局 SNAKE_CASE(与原项目一致):请求体与响应体字段均为 `user_name`、`refresh_token`、`registration_token` 等。

### 验证码机制

- 6 位随机码,存 Redis(`vCodeEmail:{email}`),默认 600 秒过期(`share.vcode.expire`);
- 发送间隔限制:一个过期周期内最多重发 10 次(`HasBeenSentVCode:{email}`);
- **一次性**:校验通过即删除(Redis Lua 原子操作),防止重放;
- 本地调试:`debug.email.no-send: true` 时不真实发邮件,验证码打印到日志。

### 邮件配置

在 `application-dev.yml` 中配置 `share.mails`(SMTP 列表,可配多个轮询发送):

```yaml
share:
  mails:
    - host: smtp.qq.com
      port: 465
      from: xxx@qq.com
      user: xxx
      pass: 授权码
      ssl: true
      starttls: false
```

### JWT 与安全

- AccessToken 默认 6 小时(`share.jwt.expire`),RefreshToken 默认 7 天(`share.jwt.refresh-expire`);
- 需登录接口默认受 Spring Security 保护,请求头携带 `Authorization: Bearer <token>`;
- 权限模型与原项目一致:status 为几即拥有 0..status 的 authority;
- 改密/重置密码后,旧的 refresh token 因 `pwdUpdateTime` 校验而失效。

### 硬性约定(与原项目共享数据)

- userId 由 MongoDB 生成(ObjectId),禁止自造;
- 实体 `MaaUser` 字段与索引注解与原项目一字不差(email 唯一索引是数据根基);
- 密码统一 BCrypt(`$2a$10$`),兼容原项目已有 1921 条数据;
- 不要新建自己的用户集合;字段演进需与原项目协调。
