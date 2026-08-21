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

先创建**统一子账号**（库存 × 密探共用一张 `sub_accounts` 表），再使用 JWT 为该账号
生成只含库存权限的本地 API Token。同一子账号也可在密探侧直接使用（如把 scopes 换成
`operator:read`，或同时包含 `inventory:*` 与 `operator:*`）：

```bash
export INVENTORY_ACCOUNT_ID="$(curl --fail-with-body -sS -X POST "$API_BASE_URL/v1/accounts" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"本地烟测账号","game":"代号鸢"}' \
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

> **统一子账号说明（2026-08）**：`/v1/inventory/accounts` 与 `/v1/operator/accounts` 已合并为
> **`/v1/accounts`**（POST 创建 / GET 列表 / PATCH 修改 / DELETE 删除）。子账号对库存、密探、
> 特别关注全局可用；token 绑定的账号为共享账号，**可访问的域由 scopes 声明**（`inventory:*` 走
> `/open-api/inventory/**`，`operator:*` 走 `/open-api/operator/**`，可混合）。删除子账号 =
> 整账号级联删除该子账号的库存、密探、特别关注数据及全部绑定 token。

每个统一子账号都持久化非空 `game`，且只允许 `代号鸢` / `如鸢`。创建缺省为 `代号鸢`；
`POST /v1/accounts`、`GET /v1/accounts`、`PATCH /v1/accounts/{accountId}` 都稳定返回该字段。
PATCH 是真正的局部修改，可只传 `{"name":"新名称"}` 或 `{"game":"如鸢"}`；修改版本不会搬迁或
删除既有库存、密探 current/record。密探新写入携带的 `game` 必须与账号一致，否则返回
`422 account_game_mismatch`。部署前先 dry-run，再按说明 APPLY
[`scripts/migrations/20260821-sub-account-game.js`](scripts/migrations/20260821-sub-account-game.js)。

### 库存联调 Smoke Test

另开一个已进入 `nix develop path:.` 的终端，将刚生成的完整 API Token 放入环境变量后运行：

```bash
export INVENTORY_API_TOKEN='replace-with-local-api-token'
./scripts/inventory-smoke.sh
```

脚本默认访问 `http://127.0.0.1:8080`，也可通过 `API_BASE_URL` 覆盖。它从 Token 自动查询绑定的
子账号（`GET /open-api/inventory/account`），不要求另外配置 `account_id`。脚本从真实目录选择 item，使用唯一
`record_id` 验证首次导入、幂等重传、409 冲突、当前库存、原始交换文档导出和直接回传导入；脚本不会打印 Token。

### 密探心纸库存与特别关注

密探心纸沿用库存交换协议 v2，不提供单独的库存写接口。使用 `POST /v1/inventory/import` 导入
`record_type=stock_snapshot`、`entity_type=agent` 的记录，再通过
`GET /v1/inventory/current?account_id=...&entity_type=agent` 查询。`full` 快照替换该子账号的完整密探库存，
`listed` 只覆盖列出的密探。条目中的 `name` 仅作为流水展示值，库存写入不会修改公共目录。

特别关注只支持普通登录 JWT，不属于 OpenAPI Token scope，也不进入库存流水或交换档案 v2：

```text
GET    /v1/inventory/agent-favorites?account_id=acc_xxx
PUT    /v1/inventory/agent-favorites/{agentId}?account_id=acc_xxx
DELETE /v1/inventory/agent-favorites/{agentId}?account_id=acc_xxx
```

GET 返回按 ID 升序排列的 `agent_ids`。PUT 与 DELETE 均幂等，所有操作先按 JWT 当前用户校验库存子账号归属。
部署前运行 [密探关注迁移](docs/inventory-agent-favorites-migration.md)。

### 密探公共图鉴与管理员管理

密探公共 API = 公共图鉴 `GET /v1/operator/catalog`（无需登录，全局只读密探目录：有哪些密探、长什么样），
与个人密探数据严格分离——`/v1/operator/**`（除 catalog）与 `/open-api/operator/**` 只能访问自己的养成档案。

### 密探当前养成资料底座

`GET /v1/operator/current?account_id=acc_xxx&game=代号鸢` 的每个 entry 在既有 `level / elite /
star_level / discs / star_stones` 上继续返回：

- `disc_loadouts`：最多两套、每套最多三个命盘，不存在 active 或“当前盘”；`discs` 始终是第一套兼容镜像；
- `combat_stats`：`attack / hp / special` 三项奇闻、扫描攻生、手动校正、观测输入与有效状态；
- `combat_stats.display_mode`：攻击力和生命力分别记忆 `auto`（公式计算）/ `manual`（手填或采集值）显示偏好；
- `revision / updated_at`：entry 级并发版本和更新时间。

旧 Mongo 行只有 `discs` 时会读取为第一套“命盘一”；旧 `main / assist` 星石槽读取为
`main1 / assist1`。`star_level` 仍是唯一化极标量，`star_stones` 仍是六槽当前装备，未增加同义字段。
读取具体游戏时，通用 `game="universal"`（兼容历史 `game="*"`）entry 可作为回退；首次 PATCH 编辑若具体游戏文档没有该 entry，
会以通用 entry 为基线写入请求的具体游戏文档，不回写通用文档。

JWT 用户可用局部校正接口（不扣库存、不写库存流水）：

```http
PATCH /v1/operator/current/{operatorId}?account_id=acc_xxx&game=代号鸢
Content-Type: application/json

{
  "level": 90,
  "star_level": 27,
  "disc_loadouts": [
    {"id": "disc_1", "name": "命盘一", "discs": [{"ot_name": "技能增伤"}]},
    {"id": "disc_2", "name": "命盘二", "discs": []}
  ],
  "star_stones": [
    {"type": "main1", "name": "攻击力", "level": 60},
    {"type": "assist1", "name": "生命值", "level": 50}
  ],
  "combat_stats": {
    "manual_attack": null,
    "display_mode": {"attack": "auto", "hp": "manual"},
    "oddities": {
      "attack": {"current": 500},
      "hp": {"current": 2600},
      "special": {"current": 15}
    }
  },
  "expected_revision": 7,
  "reason": "manual_correction"
}
```

只合并出现的顶层和 `combat_stats` 内部字段；`disc_loadouts=[]` 清空两套，
`star_stones` 出现时完整替换六槽当前装备，`star_stones=[]` 清空全部星石，缺失则保留，
槽位仅允许 `main1..main3`、`assist1..assist3` 且不可重复；
`combat_stats=null` 清除战斗资料，`manual_attack=null / manual_hp=null` 只清除对应手动校正。
`display_mode` 也按内部字段出现性合并，`attack: null` 或 `hp: null` 清除对应偏好，
显示偏好变化不会使已有观测变为 stale。若账号尚无 current 文档或 entry，`expected_revision=0`
的首次 PATCH 会创建指定游戏 entry；非零 revision 的缺失 entry 返回 `409 operator_revision_conflict`。
普通密探 `star_level` 允许 `0..31`，SP 依据公共图鉴
`sp_of` 只允许直接星级 `0..5`。奇闻上限按 rarity 固定为 3 星 `300/1560/9`、4 星
`305/1820/11`、5 星 `500/2600/15`；第三项展示名不写入用户数据。

v2 listed/full 导入继续只更新旧字段：`discs` 更新第一套并保留第二套，新增战斗资料不会因 DTO 缺字段被清空；
删除 v2 record 时会把独立的 `operator_correction_records` 校正审计与剩余 v2 record 按接收顺序重放。
v2 export 仍只输出第一套镜像 `discs`、既有 `starLevel` 和 `starStones`。

### 密探养成交换协议 v3

浏览器 JWT 使用以下接口导入客观养成快照：

```http
POST /v1/operator/import/preview
POST /v1/operator/import
Authorization: Bearer <JWT>
Content-Type: application/json
```

来源账号不是后端账号 ID 时，请求使用包装体；`account_mapping` 的 value 必须是当前 JWT 用户拥有的子账号：

```json
{
  "document": {"format": "myshare-operator-exchange", "version": 3},
  "account_mapping": {"local_default": "acc_xxx"},
  "confirm_review": false
}
```

来源 ID 已经是本人真实 `account_id` 时也可直接提交 v3 文档。preview 只返回逐 entry 的
`accepted / partial / review / rejected / unchanged`、字段差异、warning、blocking error、stale 和目标 revision，
不写 current 或库存。commit 复用同一 Schema validator 和 current 局部合并规则，写入
`operator_v3_import_records` 审计/幂等记录；相同 `record_id` 和内容重复提交返回 unchanged，内容不同返回
`idempotency_conflict`。`listed` 不删除报告外密探；`full` 删除文档外客观 entry，但文档内 entry 未出现的
第二套命盘、combat_stats 和 display_mode 保持不变。

自动采集使用账号绑定 token 和专用最小权限：

```http
POST /open-api/operator/scan-import/preview
POST /open-api/operator/scan-import/commit
Authorization: Bearer <account-bound-token>
Content-Type: application/json
```

token 必须包含 `operator:scan:write`。OpenAPI 只接受一个来源账号和
`operator_snapshot + source_kind=scan + snapshot_scope=listed`，并始终把来源账号映射到 token 绑定账号；
请求中的 `account_id` 不能选择其他目标账号。自动采集不能提交 annotation、manual 攻生校正或
`display_mode`，服务端将写入的 `combat_stats.source` 固定为 `scan`。v3 的
`equipped_star_stones` 写入现有 current `star_stones`，不会创建库存实例、扣库存或写库存流水。
服务端会把本次非空的 `observed_attack / observed_hp` 同步为对应的 `manual_attack / manual_hp`，
并把对应 `display_mode` 设为 `manual`；前端因此可继续使用既有 `auto / manual` 开关在公式值和采集值之间切换。

浏览器需要即时提示最近一次自动上报或库存更新时，可在页面打开期间订阅账号级 SSE：

```http
GET /v1/accounts/acc_xxx/events
Authorization: Bearer <JWT>
Accept: text/event-stream
```

OpenAPI scan 处理 entry 后发送 `operator_scan_import` 事件，数据包含
`account_id / operator_id / record_id / status / revision / stale / observed_status / warnings / blocking_errors / occurred_at`。
每个 OpenAPI 库存导入事务成功后发送一个 `inventory_import` 事件，数据包含导入结果和本次
`record_id / record_type / entity_type / entries[{id,count}]`。前端可以只根据事件名和时间显示“最近流水已更新”或“库存已更新”，忽略条目明细；采集端可以批量提交，不要求一个 entry 一个请求。
它不维护扫描总量或开始/结束状态，也不补发断线期间的历史动画事件；`operator_current` 和
`inventory_current` 仍是持久事实源。
服务端每 15 秒发送 SSE comment 心跳并关闭 Nginx 响应缓冲。由于原生 `EventSource` 不能设置 Bearer header，
当前 JWT 前端应使用带 `Authorization` 的流式 `fetch` 或支持自定义 header 的 SSE 客户端。

生产校验使用内置 `schema/operator-growth-exchange-v3.schema.json`，并再次以公共图鉴校验 operator/game、
普通与 SP `star_level`、命盘、六个星石槽位和按 rarity 派生的奇闻上限。稳定错误码包括
`schema_validation_failed`、`account_mapping_required`、`account_scope_mismatch`、`account_game_mismatch`、
`unknown_operator`、`invalid_star_level`、`invalid_combat_stats`、`invalid_disc_loadout`、
`invalid_equipped_star_stones`、`scan_scope_not_allowed`、`idempotency_conflict` 和
`operator_revision_conflict`。

**管理员（用户 `status >= 2`）管理公共图鉴的数据面**，即在 `/v1/admin/operator-catalog/**` 上增删改查
`operator_catalog` 字典，改动即时反映到公共图鉴与导入校验：

```text
GET    /v1/admin/operator-catalog                 # 管理端全量（含 starStones / catalogVersion / createdAt）
POST   /v1/admin/operator-catalog                 # 新增密探（Body 见 OperatorCatalogWriteRequest）
PUT    /v1/admin/operator-catalog/{operatorId}    # 更新（path/body id 必须一致）
DELETE /v1/admin/operator-catalog/{operatorId}    # 删除
```

以上端点需要 JWT 登录且必须是管理员，否则 403；失败统一返回 `OperatorErrorResponse`
（`operator_conflict` / `operator_not_found` / `schema_validation_failed` 等）。
设计见 [docs/operator-subaccounts-implementation-plan.md](docs/operator-subaccounts-implementation-plan.md) §6.5。

公共与管理员目录的每位密探都返回 `special_oddity_name`、`oddity_schema` 和 `incomplete_fields`。
奇闻值的稳定键固定为 `attack / hp / special`；管理员只维护第三项显示名称，三个上限由服务端按
rarity 派生：3 星 `300/1560/9`、4 星 `305/1820/11`、5 星 `500/2600/15`。新建目录条目必须提供
`special_oddity_name`；更新缺失或传 null 时保留旧值。存量缺名时返回 null，
`oddity_schema.special.name` 降级为“第三属性（图鉴待维护）”，且
`incomplete_fields=["special_oddity_name"]`。目录改名只更新公共展示和 `catalog_version`，不会写入
任何子账号、库存或个人密探养成数据。

内置目录当前已按游戏直接采集结果维护 116 位密探的第三奇闻名称；`史子眇`、`陈登·黍王`、`简雍`、
`孙静`、`张松` 尚无可按目录 ID 确认的采集值，继续按上述存量缺名规则返回，禁止按职业猜测。

**密探头像**：头像以 webp 文件存放在 `share.avatar.dir`（默认 `./data/avatar`，可用环境变量
`SHARE_AVATAR_DIR` 覆盖，Docker 部署请挂持久卷），文件名为 `{operatorId}.webp`，对外以
`/avatar/{operatorId}.webp` 公开读（图鉴本就无需登录）。管理员可经
`PUT /v1/admin/operator-catalog/{operatorId}/avatar` 上传（webp、≤500KB）、
`DELETE /v1/admin/operator-catalog/{operatorId}/avatar` 删除。也支持把成品
`{operatorId}.webp` 直接放进头像目录——后端初始化时会按目录回填 `avatar` 字段（只补空值、
不覆盖已上传头像），重启后端即可批量生效。详见
[docs/operator-catalog-avatar-design.md](docs/operator-catalog-avatar-design.md)。

**把账号设为管理员**：管理员判定为 `maa_user.status >= 2`（`UserService.ADMIN_STATUS`），
在 MaaBackend 数据库直接更新即可（建议先按 email 确认再改）：

```bash
mongosh 'mongodb://<host>:27017/MaaBackend' --quiet \
  --eval 'db.maa_user.updateOne({ email: "someone@example.com" }, { $set: { status: 2 } })'
```

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
