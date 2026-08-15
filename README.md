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

1. 使用 IDE 导入此项目,复制 `/src/main/resources/application-template.yml` 到同目录下,
   命名为 `application-dev.yml`,修改数据库配置以符合你自己的环境。
2. 安装 JDK 21 或以上版本。
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

## 认证链路(演示)

`AuthController` 提供了两个演示接口(接入真实业务后可删除):

- `POST /auth/demo-token?userId=xxx` 签发演示 token(生产环境的 token 应在登录成功后签发);
- `GET /auth/me` 需要登录,请求头携带 `Authorization: Bearer <token>`,
  通过 `AuthenticationHelper.requireUserId()` 获取当前用户 id。

JWT 实现基于 hutool-jwt,参考 `service/jwt` 包:
`JwtService.issueAuthToken` 签发、`verifyAndParseAuthToken` 验证,
默认无状态方案;若需要 RefreshToken 有状态管理,可在数据库中记录 jwtId。

## 测试

`./gradlew test` 运行测试。现有测试:

- `JwtServiceTest`:JWT 签发/验证/过期/伪造
- `DemoServiceTest`:Demo 服务层(MockK 模拟仓储)

## 编译与部署

1. 安装 JDK 21
2. `./gradlew bootJar` 编译
3. 复制配置文件:`cp ./build/resources/main/application-template.yml ./application-prod.yml`
4. 修改 `application-prod.yml`
5. 运行:`java -jar build/libs/BackEndV3Share-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod`

## 代码规范

- ktlint 强制检查(`./gradlew ktlintCheck`),不过关无法构建;
- 代码风格由 `.editorconfig` 定义(IntelliJ IDEA 风格,4 空格缩进)。
