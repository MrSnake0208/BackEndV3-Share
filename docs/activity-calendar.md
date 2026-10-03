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
