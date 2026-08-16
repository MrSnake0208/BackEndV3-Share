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

1. 进入开发环境：`nix develop path:.`。该环境提供 JDK 21、Git、curl 和 jq；Gradle 使用仓库内的 `./gradlew`。
2. 使用 IDE 导入此项目,复制 `/src/main/resources/application-template.yml` 到同目录下,
   命名为 `application-dev.yml`,修改数据库配置以符合你自己的环境。
3. 需要本地的 MongoDB 和 Redis 环境。
4. 运行 `./gradlew bootRun`。

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
