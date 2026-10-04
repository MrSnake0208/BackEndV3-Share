# 活动日历与用户建议 API

提供手工目录、招募卡池聚合，以及独立的用户建议和维护成员审核；不提供物理删除或导入接口。

## 管理员测试阶段查询

`GET /v1/activity-calendar` 需要 JWT 登录及 `AdminAuthorizationService.hasAnyAdminCapability` 管理能力，包含内容维护角色与反馈管理成员。未登录返回 401；普通用户返回 403 `admin_testing_only`，不读取日历业务数据。前端日历读取携带认证并绑定当前身份。管理写入/审核仍独立要求 `activity_calendar:write`。可选参数：

- `game`：`代号鸢` / `如鸢`，省略查询全部。
- `from` / `to`：ISO 日期，闭区间 overlap（`end_date >= from && start_date <= to`）；可只提供一端，倒置范围返回 422。
- `category`：可重复或用逗号分隔，取并集。`ACTIVITY`、`RECRUITMENT`、`LOGIN`、`SHOP`、`MAINTENANCE`、`OTHER`。

成功响应沿用 `ApiResult`，列表为 `data.items`。按 `start_date`、`start_time`（未填在前）、`id` 升序。字段采用 snake_case：

```json
{
  "id": "evt_<uuid>",
  "source_type": "MANUAL",
  "source_ref": null,
  "game": "如鸢",
  "title": "活动名称",
  "category": "ACTIVITY",
  "start_date": "2026-10-03",
  "end_date": "2026-10-17",
  "start_time": null,
  "end_time": null,
  "time_zone": "Asia/Shanghai",
  "description": null,
  "source_url": null
}
```

手工项来自 `activity_calendar_events`。招募项直接从 `recruitment_catalog` 派生，`id=recruitment:<poolId>`、`source_type=RECRUITMENT_POOL`、`source_ref=<poolId>`、`category=RECRUITMENT`。仅 enabled=true 且两端日期完整、顺序有效的卡池派生；不使用 seed，不补日期，不写回。停用手工活动不公开。

日期使用 LocalDate，Mongo 存 ISO 字符串；未提供时间时不补造具体时刻。

## 管理

以下全部要求登录且拥有 `activity_calendar:write`：

- `GET /v1/admin/activity-calendar`：公共筛选外支持 `enabled` 和标题 `search`；不传 enabled 时包含停用手工项。条目为 `{item, read_only, enabled, version, source_note, created_by, created_at, updated_by, updated_at}`。招募派生项只读，version 为 null，创建审计为空；仅启用且日期有效的卡池参与派生。
- `POST /v1/admin/activity-calendar`：创建手工项，返回 `data` 管理条目；不接受 expected_version。初始版本为 0。
- `PUT /v1/admin/activity-calendar/{id}`：完整替换可编辑字段，必须携带刚读取的 expected_version；返回最新管理条目。停用通过 enabled=false，保留创建信息与文档。招募派生 ID 不允许写入，应在招募目录维护。

写入示例：

```json
{
  "game": "如鸢",
  "title": "活动名称",
  "category": "ACTIVITY",
  "start_date": "2026-10-03",
  "end_date": "2026-10-17",
  "time_zone": "Asia/Shanghai",
  "enabled": true,
  "expected_version": 0
}
```

POST 示例需去掉 expected_version。必填 game/title/category/start_date/end_date；time_zone 默认 Asia/Shanghai，enabled 默认 true。可选 start_time/end_time、description、source_url、source_note；PUT 省略可选内容会清空或恢复默认值。

校验：标题 trim 后 1..120；description/source_note <=1000；结束日期不早于开始日期；时间须成对为 HH:mm，同日结束时间不早于开始时间；合法 ZoneId；来源链接仅 http/https 且有主机，最长 2048。手工类别不接受 RECRUITMENT。未知字段、错误类型和无效字段返回 422 `schema_validation_failed`；找不到活动返回 404 `activity_calendar_not_found`；expected_version 过期及 Mongo 保存竞争均返回 409 `activity_calendar_version_conflict`。领域错误结构为 `{"error":{"code":"…","message":"…"}}`。

`SUPER_ADMIN`、`PLATFORM_ADMIN` 均可维护；`ACTIVITY_CALENDAR_EDITOR` 单独绑定时只获本权限，不获得其它平台、反馈或招募管理权限。权限仍要求账号 activated。前端仅同步权限契约及角色标签。


## 用户建议与审核

建议独立保存在 `activity_calendar_suggestions`，不会参与公共日历查询。以下路径显式要求 JWT；普通用户提交与查询本人记录不要求 activated、内测资格、子账号或维护权限。

| 方法与路径 | 授权 | 返回 |
| --- | --- | --- |
| `POST /v1/activity-calendar/suggestions` | 登录且具备管理能力 | 建议详情 |
| `GET /v1/activity-calendar/suggestions/mine` | 登录且具备管理能力，只查询本人 | 分页列表 |
| `GET /v1/activity-calendar/suggestions/{id}` | 具备管理能力，且为本人或具有 `activity_calendar:write` | 建议详情 |
| `GET /v1/admin/activity-calendar/suggestions` | `activity_calendar:write` | 分页审核队列 |
| `POST /v1/admin/activity-calendar/suggestions/{id}/accept` | `activity_calendar:write` | 最终建议详情 |
| `POST /v1/admin/activity-calendar/suggestions/{id}/reject` | `activity_calendar:write` | 最终建议详情 |

提交示例：

```json
{
  "game": "如鸢",
  "title": "活动名称",
  "category": "ACTIVITY",
  "start_date": "2026-10-03",
  "end_date": "2026-10-17",
  "source_url": "https://example.com/announcement",
  "submission_note": "给审核员的私人说明",
  "client_request_id": "unique-client-request-id"
}
```

活动资料使用现有校验，固定 `Asia/Shanghai` 日期；仅支持五种手工类别。source_url 必填，http/https，最多 2048 字符；可选 start_time/end_time（成对）、description（最多1000字符）、submission_note（trim 后最多1000字符）。client_request_id 为 1..128 位字母、数字、下划线或短横线。同用户同标识同规范化资料复用旧结果；不同资料返回409。同标识在不同用户间互不影响。用户边界拒绝 enabled、time_zone、source_note、身份、状态及审核字段；未知字段和非严格类型返回422。

分页参数 `page` 默认1且至少1，`page_size` 默认20且范围1..100，越界返回422；列表为 `data.{items,total,page,page_size}`。本人列表可按 `status` 筛选（PENDING/ACCEPTED/REJECTED），按 created_at/id 降序。审核队列另外支持 game，按 created_at/id 升序；未传 status 时包含所有状态。唯一索引 `(submitterId,clientRequestId)` 防并发重复，分页索引覆盖本人、状态、游戏及时间/ID排序。

详情为 `{id,submitter_id,created_at,original,submission_note,status,version,reviewed_by,reviewed_at,review_note,event_id,accepted_snapshot,current_event}`。original 仅包含用户提交的活动公开字段（不含 time_zone）；client_request_id 不返回。不存在与非本人越权均返回404 `activity_calendar_suggestion_not_found`，不暴露他人私人内容。accepted_snapshot 是采纳时的公开 `ActivityCalendarItem`；current_event 是 `{item: ActivityCalendarItem, enabled}`，不包含正式活动的 source_note 或管理审计。后续修改/停用不影响原始建议与采纳快照；活动停用仍可由提交人查看当前启用情况。

采纳示例：

```json
{
  "expected_version": 0,
  "event": {
    "game": "如鸢",
    "title": "核对后的活动名称",
    "category": "ACTIVITY",
    "start_date": "2026-10-03",
    "end_date": "2026-10-17",
    "source_url": "https://example.com/announcement",
    "source_note": "仅管理端可见的来源备注"
  },
  "review_note": "已根据公告核对"
}
```

event 使用既有手工活动写入字段，采纳同样要求来源链接必填，不接受内层 expected_version；time_zone 必须为 Asia/Shanghai，enabled 服务端固定 true。采纳在 `hubTransactionTemplate` 内检查 PENDING+expected_version，复用正式活动校验和创建，再条件写 ACCEPTED、审核信息、关联和快照；失败整体回滚。部署 Mongo 必须支持事务（replica set/sharded），不得降级为非原子双写。已ACCEPTED重试返回旧结果，忽略新编辑内容；已REJECTED不可采纳。竞争事务返回既有采纳结果或409 `activity_calendar_suggestion_conflict`，不得盲目重试旧稿。

不采纳 body 为 `{"expected_version":0,"review_note":"重复活动，请补充不同资料"}`，原因 trim 后须1..1000字符。单文档 PENDING+version 条件更新，终态不可再次审核。采纳的 review_note 可选且最多1000字符。私人 submission_note 不自动进入正式 description/source_note。原始资料与请求身份提交后不可改写。

未登录401，缺少维护权限403，字段无效422 `schema_validation_failed`，状态/版本或提交标识资料冲突409 `activity_calendar_suggestion_conflict`。沿用日历领域错误结构。

定向验证（用户/CI执行）：

```bash
./gradlew test --tests '*ActivityCalendarSuggestionServiceTest' --tests '*ActivityCalendarSuggestionControllerContractTest' --tests '*ActivityCalendarSecurityTest' --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarControllerContractTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarSuggestionMongoTest' --tests '*ActivityCalendarMongoTest' --console=plain
```

integrationTest 只使用 TestMongo 自有、可销毁的副本集容器，验证唯一重试索引、所有权/分页、事务回滚及并发审核；不接受开发或生产数据库连接。


## 管理员测试阶段验证（2026-10-04）

风险 L3；日历读取及建议提交/本人列表/详情统一按现有管理能力限制。未改变日历查询范围、字段、持久化和审核写权限。安全链测试覆盖未登录 401、非管理员 403、管理员无日历写权限时可读取/提交本人建议、管理目录仍拒绝无写权限用户；契约测试继续覆盖原数据契约与资料校验。

回归由用户/CI执行（cwd BackEndV3-Share；此组不使用容器）：

```bash
./gradlew test --tests '*ActivityCalendarSecurityTest' --tests '*ActivityCalendarControllerContractTest' --tests '*ActivityCalendarSuggestionControllerContractTest' --console=plain
```

本轮已执行 `./gradlew compileKotlin compileTestKotlin --console=plain` 与 `git diff --check`，均通过。上面的回归测试尚未执行；未启动/重启后端服务。


## 私人本期订阅（2026-10-05）

所有 `/v1/activity-calendar/subscriptions` 接口要求JWT和任意管理能力（沿用管理员测试范围），通过认证用户校验本人子账号；共享Token、普通用户不能访问。活动必须与账号游戏一致；不存在或不属于本人的账号返回404。公共响应不包含私人进度。

| 方法与路径（以下相对subscriptions） | 契约 |
| --- | --- |
| GET 根路径 | account_id必填；可选from/to/category；默认今天到90天后，范围差不超过100天。返回items、unavailable_items、subscribed_count |
| GET /summary | account_id必填；完整集合中筛选已开始、未结束、未完成且7天内截止，再排序取3项；返回items、total_pending、subscribed_count |
| GET /{eventId} | account_id必填；包括已取消历史；无记录404 subscription_not_found |
| PUT /{eventId} | account_id、expected_version、subscribed；首次创建expected_version=null，后续使用读取的版本 |
| PUT /{eventId}/progress | account_id、expected_version、completed、checklist；整体替换私人进度 |

请求严格拒绝缺失/未知字段、错误类型、负数/溢出版本；缺失必需查询参数与输入不合法返回422。checklist最多50项，每项id/title/completed必填；id为1–80位字母数字下划线或连字符且不重复，title去首尾空白后1–80字。非空清单要求completed=false，响应整体完成由所有项目计算；空清单使用请求completed（包括客户端删除最后一项时提交的草稿整体完成状态）。新项目默认未完成是UI默认值，接口允许用户主动勾选的新项目。

存储集合 `activity_calendar_subscriptions`，唯一键(userId,accountId,eventId)，使用@Version和expected_version防覆盖；重复创建、旧版本和事务写冲突返回409。取消只切换subscribed，保留进度；列表只包含活跃订阅，详情可读取已取消记录用于恢复。来源只保存稳定eventId，手工活动与 `recruitment:<poolId>` 通过既有公开投影批量解析，读请求不写库，无活动资料复制或管理备注泄露。

有效来源截止后可编辑已订阅进度及取消，不可新增/恢复；停用/消失/变更游戏后item=null，进入unavailable_items，不提醒、不修改清单，但可取消。公开投影增加可空的start_at/end_at，精确截止采用instant排他边界；日期模式按Asia/Shanghai日历日截止，保留原公开日期统计。

订阅及进度写入复用Hub事务和子账号fence。账号删除在同一事务中清理全部订阅（包括取消记录），并发写入不能留下孤儿。存在订阅历史时更换游戏返回409 activity_calendar_game_locked，重命名不受影响。无历史账号保持原行为。

风险L3。已新增Service/Contract/Mongo测试并更新Security、SubAccountService测试；仅完成源码及测试编译、15个受影响文件严格ktlint，尚未执行业务回归。最小后端回归（cwd BackEndV3-Share，JDK21，由用户/CI执行）：

```bash
./gradlew test --tests '*ActivityCalendarSubscriptionServiceTest' --tests '*ActivityCalendarSubscriptionControllerContractTest' --tests '*ActivityCalendarSecurityTest' --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarControllerContractTest' --tests '*SubAccountServiceTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarSubscriptionMongoTest' --tests '*AccountIndexMongoTest' --console=plain
```

Mongo测试必须使用项目TestMongo管理的自有临时容器，不连接已有开发/生产实例。integrationTest验证唯一约束、CAS、故障回滚、取消历史清理及账号删除竞争；普通test通过不能替代它。先发布兼容后端再发布前端；本次未部署、未改变开放范围。
