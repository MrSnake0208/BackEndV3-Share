# 活动日历与用户建议 API

提供手工目录、招募卡池聚合、管理员 JSON 批量导入，以及独立的用户建议和维护成员审核；不提供物理删除。

## 招募 UP 名称（2026-10-12）

公开及管理日历项新增只读 `up_agent_names: string[]`，手工项和缺少有效名单的旧目录返回 `[]`。仅从公共 `recruitment_catalog` 读取：池 `up_status=verified/partial`，槽 `active=true`、`operator_id` 非空且名称非空时返回修剪后的名称，保序去重。`partial` 只返回其中已正式绑定的槽；未映射占位、停用槽、`unknown/selection` 池不表示已确认 UP，不填入名单。`source_up_agent_names` 是独立来源资料，不作为正式名单回退，也不输出候选名称。

例如 `{"title":"卡池名","source_type":"RECRUITMENT_POOL","up_agent_names":["甲","乙"]}`；标题、ID、来源、游戏、类型和日期保持原义，hidden/enabled 过滤不变。不读取个人档案、账号或出货记录，不增加持久化字段，不接受手工/建议/导入写入。旧客户端可忽略新增字段，新客户端应兼容旧接口缺字段。

最小验证（仓库根）：`./gradlew test --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarControllerContractTest' --tests '*ActivityCalendarSubscriptionServiceTest' --console=plain`。

## 可选细分类别（2026-10-09）

持久化 `category` 及查询值继续使用 `ACTIVITY/RECRUITMENT/LOGIN/SHOP/MAINTENANCE/OTHER`，不为展示分组迁移旧记录。手工事件、写请求、公开/管理响应、建议原始资料与采纳快照新增 `subtype`、`subtype_detail`，均可省略或为 null，历史 Mongo 文档缺字段时默认为 null。PUT 仍为完整替换：省略或 null 会清除细类/细目。

| category | 允许的 subtype |
| --- | --- |
| ACTIVITY | LARGE_EVENT（大型活动）、MEDIUM_EVENT（中型活动）、UNDERGROUND（地宫）、TAISHAN_FU（泰山府）、SIGN_IN（签到）、FURNITURE（家具） |
| LOGIN | SIGN_IN；旧记录未标注时前端仍展示签到 |
| SHOP | RECRUITMENT_SHOP（卡池商店）、ACTIVITY_SHOP（活动商店） |
| OTHER | MAINTENANCE（维护）、STAR_POOL（星池） |
| MAINTENANCE | MAINTENANCE；旧记录未标注时前端仍展示维护 |
| RECRUITMENT | 不接受手工写入；卡池由目录派生 |

`subtype_detail` 唯一值为 `MYSTERIOUS_CAVE`（神秘洞窟），且必须有 `category=ACTIVITY`、`subtype=FURNITURE`，即“活动 → 家具 → 神秘洞窟”。未知代码、数字/数字字符串枚举、中文作为代码、跨类别细类以及无家具父级的细目返回 422。建议提交与采纳沿用同一严格解码/业务校验，仍拒绝服务端字段及越权写入。

`RECRUITMENT_POOL` 的日历响应增加只读 `pool_type`，直接取 `RecruitmentCatalogPool.poolType` 原始值。该字段不持久化到手工事件、不接受手工/建议/导入写入。派生 ID `recruitment:<poolId>`、`source_ref`、`read_only`、停用/隐藏/缺日期过滤均保持原义。自由文本类型的已知别名、组合优先级和中性回退由前端与招募档案共用的 `recruitmentPoolTypes.js` 处理；不按活动名称推断。

批量 JSON 仍为 v1、1–200 条。自然键与导入 ID 不增加细类字段；`subtype/subtype_detail` 属于内容差异：已有记录同键则跳过并逐字段提示，文件内同键细类不同则冲突阻止提交。旧 JSON 可以照常导入；历史分类补全请使用带版本校验的目录编辑。不同细类不会变相创建重复活动。

没有新增档期关联 ID，不自动合并家具/神秘洞窟或地宫/泰山府实体，不改起止时间、不复制活动、不取消独立订阅。家具与洞窟同档时间由维护者核对；类型关联不能证明两条记录属于同一期。无需数据库迁移、索引变更或定时任务。

## 管理员 JSON v1 批量导入

两个接口均需要 JWT + `ACTIVITY_CALENDAR_WRITE`（`activity_calendar:write`）；匿名 401、无权限 403，校验前检查权限。仅创建手工活动，不导入卡池或招募，不更新、覆盖、删除已有记录。

`POST /v1/admin/activity-calendar/import/preview` 的请求为 `{"json":"原始 JSON 文本"}`。成功返回 `data` 报告：`total`、`ready`（待新建）、`existing`、`duplicates`、`invalid`、`conflicts`、`public_count`（待新建并公开）、`created`、`failed`、`preview_token`、`rows`。预览无数据库写入。

`POST /v1/admin/activity-calendar/import/commit` 使用同一原文及预览令牌：`{"json":"原始 JSON 文本","preview_token":"预览返回的值"}`。服务端重新解码、业务校验、查询重复项及比对预览快照。缺少令牌、原文变化或相关数据库记录变化返回 409 `calendar_import_preview_expired`，本次整批未开始写入，须重新预览。无效行或文件内内容冲突返回 422，整批不写入。前端确认不是服务端安全边界。

每行 `row` 为活动数组条目序号（从 1 起，不是文本物理行号），`status` 为 `READY / EXISTS / DUPLICATE / INVALID / CONFLICT / CREATED / FAILED`；`title/game` 用于定位，`data` 为规范化预览（非法行为空），`issues` 为 `{field,message}` 列表，`event_id` 为匹配或创建的记录。JSON 语法错误另提供文本行列位置；单行业务错误指出字段。

推荐文件格式，可直接复制（两条均为本地验收示例）：

```json
{
  "schema_version": 1,
  "items": [
    {
      "game": "代号鸢",
      "title": "导入验收示例活动",
      "category": "ACTIVITY",
      "start_date": "2026-10-10",
      "end_date": "2026-10-20",
      "start_time": null,
      "end_time": null,
      "time_zone": "Asia/Shanghai",
      "description": "仅用于本地验收，请调整日期和标题。",
      "source_url": "https://example.com/notice",
      "source_note": "管理员内部备注",
      "enabled": true
    },
    {
      "game": "如鸢",
      "title": "导入验收示例登录活动",
      "category": "LOGIN",
      "start_date": "2026-10-12",
      "end_date": "2026-10-15",
      "start_time": "05:00",
      "end_time": "23:59",
      "enabled": false
    }
  ]
}
```

兼容纯顶层数组；包裹格式必须为整数 `schema_version=1`，不支持未知版本。UTF-8 文件及 JSON 原文最多 **1 MiB（1,048,576 字节）**，每批 **1–200 条**。请求包因 JSON 字符串转义允许最多 `6 MiB + 4096` 字节，不能绕过原文 1 MiB 校验。服务端只接收文本，不接受本地文件路径。拒绝重复对象字段、尾随 JSON、未知字段、错误类型和隐式数值/布尔转文本。

| 字段 | 要求与默认值 |
| --- | --- |
| `game/title/category/start_date` | 必填；游戏仅 `代号鸢/如鸢`；标题 trim 后 1–120 字；类别仅 `ACTIVITY/LOGIN/SHOP/MAINTENANCE/OTHER`；日期严格 `YYYY-MM-DD`，结束不得早于开始 |
| `end_date` | 可省略或为 null，表示持续进行；填写时必须为有效 `YYYY-MM-DD` 且不早于开始日期；补齐时应编辑原记录，导入不同结束日期会形成另一条自然键 |
| `start_time/end_time` | 可选或 null，为 `HH:mm`；有结束日期时须成对，无结束日期时仅可填开始时间；同日结束时间不得早于开始 |
| `time_zone/enabled` | 缺省分别 `Asia/Shanghai` / `true`，显式 null 不接受；时区遵循单条创建的有效 ZoneId 规则；日期/时间按该时区记录，未填精确时间不补造时刻，推荐统一使用 Asia/Shanghai |
| `description/source_note` | 可选或 null，各 ≤1000 字；与现有服务器单条创建一致保留文本，`source_note` 仅管理端可见 |
| `source_url` | 可选或 null；trim 后空值归为 null；非空须 http(s)、有域名且 ≤2048 字，与单条创建共用校验 |

拒绝 `id/version/expected_version/source_type/source_ref` 等服务器字段；`RECRUITMENT` 派生项始终只读，不参与导入目标或覆盖。

自然键由游戏、类别、trim 后标题、起止日期、成对精确时间及时区组成。同名不同游戏/期次可共存。已有手工记录（含停用、历史随机 ID）同键则跳过；说明、链接、内部备注或启用值不同，在预览中逐字段提示并保留原内容。文件内完全重复跳过；同键不同内容为冲突，须修正整批再提交。

导入使用 `evt_import_<自然键SHA256>` 和 Mongo `insert`，依靠既有 `_id` 唯一约束防止并发批量导入重复；不新建索引、不迁移或清理历史数据，不调用 save 覆盖。重复插入会重新读取核对，内容相同跳过、不同则该行失败。已导入活动被改成另一自然键而占用旧导入标识时明确冲突，须人工检查。此保证针对批量导入；单条新建保留原有随机 ID 语义，避免在导入期间用单条入口并发创建同一活动。

提交采用**部分成功**，不依赖 Mongo 事务：HTTP 200 也可能含 `failed>0`，必须查看逐行结果，不可只看 HTTP 状态。错误/超时可能意味着写入未确认；重新预览原 JSON，再确认导入即可安全重试，已创建行跳过。第二次导入相同原文显示已存在（文件内重复行仍显示文件内重复），不创建新记录。成功项复用单条创建审计字段及版本初始化；公共查询仍仅展示启用活动。

最小定向验证（不接触现有数据库）：

```bash
./gradlew test --tests '*ActivityCalendarImportServiceTest' --tests '*ActivityCalendarImportContractTest' --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarControllerContractTest' --tests '*ActivityCalendarSecurityTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarImportMongoTest' --tests '*ActivityCalendarMongoTest' --console=plain
```

第二条需要 Docker，由 TestMongo 创建并清理专属容器/随机测试库。

## 内测用户查询

`GET /v1/activity-calendar` 需要 JWT 与全站内测资格，不要求管理能力。未登录返回 401；无资格/维护返回对应 `beta_*` 403，资格查询失败返回 503，业务服务不执行。前端请求绑定当前身份。管理写入/审核仍独立要求 `activity_calendar:write`。可选参数：

- `game`：`代号鸢` / `如鸢`，省略查询全部。
- `from` / `to`：ISO 日期，闭区间 overlap（`(end_date == null || end_date >= from) && start_date <= to`）；可只提供一端，倒置范围返回 422。
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

POST 示例需去掉 expected_version。必填 game/title/category/start_date；end_date 可省略或为 null，表示持续进行；time_zone 默认 Asia/Shanghai，enabled 默认 true。可选 start_time/end_time、description、source_url、source_note；PUT 省略可选内容会清空或恢复默认值。

校验：标题 trim 后 1..120；description/source_note <=1000；已填写的结束日期不早于开始日期；时间为 HH:mm，有结束日期时须成对，无结束日期时仅可填开始时间；同日结束时间不早于开始时间；合法 ZoneId；来源链接仅 http/https 且有主机，最长 2048。手工类别不接受 RECRUITMENT。未知字段、错误类型和无效字段返回 422 `schema_validation_failed`；找不到活动返回 404 `activity_calendar_not_found`；expected_version 过期及 Mongo 保存竞争均返回 409 `activity_calendar_version_conflict`。领域错误结构为 `{"error":{"code":"…","message":"…"}}`。

`SUPER_ADMIN`、`PLATFORM_ADMIN` 均可维护；`ACTIVITY_CALENDAR_EDITOR` 单独绑定时只获本权限，不获得其它平台、反馈或招募管理权限。权限仍要求账号 activated。前端仅同步权限契约及角色标签。


## 用户建议与审核

建议独立保存在 `activity_calendar_suggestions`，不会参与公共日历查询。以下路径显式要求 JWT；普通用户提交与查询本人记录需要全站内测资格，不额外要求子账号或维护权限。

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

活动资料使用现有校验，固定 `Asia/Shanghai` 日期；仅支持五种手工类别。source_url 必填，http/https，最多 2048 字符；可选 start_time/end_time (paired when end_date is set; start_time only when open-ended)、description（最多1000字符）、submission_note（trim 后最多1000字符）。client_request_id 为 1..128 位字母、数字、下划线或短横线。同用户同标识同规范化资料复用旧结果；不同资料返回409。同标识在不同用户间互不影响。用户边界拒绝 enabled、time_zone、source_note、身份、状态及审核字段；未知字段和非严格类型返回422。

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


## 内测准入验证（2026-10-12）

风险 L3；日历读取、建议、订阅及私人事项统一使用 Beta 准入，取消管理员测试门禁。安全链测试覆盖未登录、无内测资格、维护、资格查询失败、普通内测用户可用，以及管理目录/审核写权限保留。账号隔离、字段与持久化语义不变。

回归由用户/CI执行（cwd BackEndV3-Share；此组不使用容器）：

```bash
./gradlew test --tests '*ActivityCalendarSecurityTest' --tests '*ActivityCalendarControllerContractTest' --tests '*ActivityCalendarSuggestionControllerContractTest' --console=plain
```

本轮回归由用户/CI 执行；未启动或重启后端服务。


## 私人本期订阅（2026-10-05）

所有 `/v1/activity-calendar/subscriptions` 接口要求 JWT 与全站内测资格，通过认证用户校验本人子账号；共享 Token 不能访问，普通内测用户可使用。活动必须与账号游戏一致；不存在或不属于本人的账号返回404。公共响应不包含私人进度。

| 方法与路径（以下相对subscriptions） | 契约 |
| --- | --- |
| GET 根路径 | account_id必填；可选from/to/category；默认今天到90天后，范围差不超过100天。返回items、unavailable_items、subscribed_count |
| GET /summary | account_id必填；返回全部未完成订阅数，及今日进行中/近72小时截止（与“近3天结束”同一时间边界）优先的最多3项（详见下文）；返回items、total_pending、subscribed_count |
| GET /{eventId} | account_id必填；包括已取消历史；无记录404 subscription_not_found |
| PUT /{eventId} | account_id、expected_version、subscribed；首次创建expected_version=null，后续使用读取的版本 |
| PUT /{eventId}/completion | account_id、expected_version、completed；只更新本账号整期完成，保留清单/备忘，旧版本409，相同版本同状态不写入 |
| PUT /{eventId}/progress | account_id、expected_version、completed、checklist；可选progress_mode、memo_title、memo_note、manual_completed；新checklist模式仅替换清单，旧请求保留兼容（见文末） |

请求严格拒绝缺失/未知字段、错误类型、负数/溢出版本；缺失必需查询参数与输入不合法返回422。checklist最多50项，每项id/title/completed必填；id为1–80位字母数字下划线或连字符且不重复，title去首尾空白后1–80字。未传manual_completed的旧协议仍要求非空清单completed=false，响应活动级完成统一按manualCompleted明确true判断。Checklist与旧completed不再推导整期完成；详见下文兼容契约。新项目默认未勾选，允许主动勾选。

私人备忘字段（2026-10-09）：响应使用snake_case `memo_title`（默认“本期备忘”）及 `memo_note`（默认空串），Mongo沿用camelCase `memoTitle`/`memoNote`，实体默认值兼容历史缺字段记录；读取不触发写入或迁移。PUT省略任一新字段时保留服务器现值；显式null/错误类型拒绝422，memo_title空白拒绝422、原始文本最多60字、保存时trim；memo_note原样多行纯文本最多1000字，空字符串表示清空。旧客户端继续保存checklist/completed，不会抹掉新客户端备忘。标题与备注不参与isCompleted、仅未完成筛选或首页摘要；所有字段同一次@Version/CAS写入。取消/恢复保留整份备忘，权属仍为(userId,accountId,eventId)。先升级后端再启用发送备忘字段的前端，旧后端严格decoder会拒绝新字段。

存储集合 `activity_calendar_subscriptions`，唯一键(userId,accountId,eventId)，使用@Version和expected_version防覆盖；重复创建、旧版本和事务写冲突返回409。取消只切换subscribed，保留进度；列表只包含活跃订阅，详情可读取已取消记录用于恢复。来源只保存稳定eventId，手工活动与 `recruitment:<poolId>` 通过既有公开投影批量解析，读请求不写库，无活动资料复制或管理备注泄露。

有效来源截止后可编辑已订阅进度及取消，不可新增/恢复；停用/消失/变更游戏后item=null，进入unavailable_items，不进入今日提醒、不修改清单，但可取消或管理活动完成。公开投影增加可空的start_at/end_at，精确截止采用instant排他边界；日期模式按Asia/Shanghai日历日截止，保留原公开日期统计。

订阅及进度写入复用Hub事务和子账号fence。账号删除在同一事务中清理全部订阅（包括取消记录），并发写入不能留下孤儿。存在订阅历史时更换游戏返回409 activity_calendar_game_locked，重命名不受影响。无历史账号保持原行为。

风险L3。已新增Service/Contract/Mongo测试并更新Security、SubAccountService测试；仅完成源码及测试编译、15个受影响文件严格ktlint，尚未执行业务回归。最小后端回归（cwd BackEndV3-Share，JDK21，由用户/CI执行）：

```bash
./gradlew test --tests '*ActivityCalendarSubscriptionServiceTest' --tests '*ActivityCalendarSubscriptionControllerContractTest' --tests '*ActivityCalendarSecurityTest' --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarControllerContractTest' --tests '*SubAccountServiceTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarSubscriptionMongoTest' --tests '*AccountIndexMongoTest' --console=plain
```

Mongo测试必须使用项目TestMongo管理的自有临时容器，不连接已有开发/生产实例。integrationTest验证唯一约束、CAS、故障回滚、取消历史清理及账号删除竞争；普通test通过不能替代它。先发布兼容后端再发布前端；本次未部署、未改变开放范围。

细分类别变更的最小隔离验证（cwd BackEndV3-Share）：

```bash
./gradlew test --tests '*ActivityCalendarServiceTest' --tests '*ActivityCalendarImportServiceTest' --tests '*ActivityCalendarSuggestionServiceTest' --tests '*ActivityCalendarControllerContractTest' --tests '*ActivityCalendarSuggestionControllerContractTest' --tests '*ActivityCalendarImportContractTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarMongoTest' --tests '*ActivityCalendarSuggestionMongoTest' --tests '*ActivityCalendarImportMongoTest' --console=plain
```

第二条要求 Docker，由测试独占临时容器，绝不能改用开发/生产 Mongo。新增测试覆盖字段缺失的旧文档、枚举 BSON 往返、原始建议/采纳快照及公开响应、独立记录计数；既有导入隔离测试覆盖重复、并发与预览过期。不会因为 Docker 未运行而跳过有效断言。

## 活动级完成与Checklist兼容（2026-10-12）

`completed` 响应只取 `manualCompleted == true`，与Checklist完全独立。Mongo字段/JSON字段仍为manualCompleted/manual_completed，不新增集合、唯一索引或批量迁移；缺失/null/false均未完成，旧completed与全勾选清单不构成整期完成证据。保留明确历史manualCompleted=true、全部旧备注、清单ID/标题/顺序/勾选。新订阅默认未完成。

`PUT /v1/activity-calendar/subscriptions/{eventId}/completion` 严格接受 `{account_id, expected_version, completed}`；completed必须Boolean，拒绝null、未知字段、用户ID、缺失/溢出版本。JWT/Beta门禁与账号归属、Hub事务、账号fence和@Version CAS沿用。只有活跃订阅、相同账号游戏可修改；有效/过期/不可用来源都允许管理完成历史。只改manualCompleted与更新时间/版本，不覆盖Checklist或备忘。相同版本、相同已保存状态为无写入no-op；旧版本包括旧版本重试一律409，须重新读取后确认，避免盲目覆盖其它设备更新。

旧 `/progress` 协议继续校验并保存显式历史字段；省略manual_completed保持现值。`progress_mode="checklist"` 时completed必须false，禁止同时提供memo_title/memo_note/manual_completed，仅替换清单，保留其它历史字段。未改动子项标题原样保留，新/改名子项trim；最多50项，稳定唯一ID、名称边界不变。清单全勾选/清空/删除不改变活动级完成。

`GET /summary` 一次读取活跃订阅并批量join公共目录。total_pending是所有未完成订阅数，包含过期或不可用；subscribed_count是全部活跃订阅数。items最多3项，仅可用未结束且（已开始或近72小时截止（与“近3天结束”同一时间边界））的未完成活动，按精确截止/上海日期截止排序，长期活动最后；item数不代表总待办数。精确时间采用instant排他边界，日期模式保留上海日历日结束语义。只读，不发通知、不写库。

完成不改变公共目录、订阅关系或其它账号。取消后历史不删，恢复保留完成/清单/备忘；新档期须使用新eventId（手工ID或recruitment:poolId），同ID日期修正不会重置状态。账号删除仍事务级清理订阅并防孤儿；不可用item=null时不泄露非公共数据，仅返回当前用户历史。

升级先后端再前端。回退前端保留存储，回退旧后端可能重新按清单解释completed；不建议只回退后端语义。无数据库迁移或回填。

最小验证（cwd BackEndV3-Share；integrationTest仅使用TestMongo独占临时容器）：

```bash
./gradlew test --tests '*ActivityCalendarSubscriptionServiceTest' --tests '*ActivityCalendarSubscriptionControllerContractTest' --tests '*ActivityCalendarSecurityTest' --console=plain
./gradlew integrationTest --tests '*ActivityCalendarSubscriptionMongoTest' --console=plain
```

## 私人日历事项 API（2026-10-10）

独立集合 `activity_calendar_private_items` 存储随机 `pci_UUID`、服务端定义的 user/account/game、title/date/endDate/time/note、`@Version`、createdAt/updatedAt（毫秒精度）。不写入公共事件/订阅集合，不进入导入、建议审核、公开聚合、RSS或分享URL，无迁移。索引 `(userId, accountId, game, date, _id)` 支持本人账号日期查询，`_id` 自带唯一约束。`date` 继续存开始日，`endDate` 存结束日；旧文档缺失/null时有效结束日等于date，读取不回写。保留原索引，无全量迁移；重叠查询使用owner/game索引前缀和date上界，再过滤endDate，不新增索引（超长历史积累时按实际explain评估）。

所有接口在 `/v1/activity-calendar/private-items` 下，使用既有 snake_case ApiResult、JWT 及全站内测门禁。每次重新验证JWT用户拥有 `account_id`，游戏从账号推导，所有文档查询包含 user/account/game。不存在和非本人记录一致404；非本人账号使用同一 `account_not_found` 响应。管理员不能因此读取其他用户私人事项。

| 方法 | 参数 / 行为 |
| --- | --- |
| GET 根路径 | 必填 `account_id/from/to`，ISO日期、含首尾最多100天；按闭区间重叠 `date<=to && (endDate>=from || (endDate=null && date>=from))` 查询，按开始日期/时间/id排序，读1001条用于检测超限，超过1000条422要求缩小范围；返回 `{items: [...]}`，无隐式截断 |
| POST 根路径 | JSON必填 `account_id/title/start_date/end_date`（或旧 `date` 单日格式）；可选 `time/note`；返回新记录 |
| GET `/{id}` | query `account_id`；返回本人记录 |
| PUT `/{id}` | JSON同创建，并必填 `expected_version`；更新区间；省略time/note保留已有内容，显式null时间/空说明可清空；CAS成功递增版本 |
| DELETE `/{id}` | query `account_id/expected_version`；按版本条件删除，返回成功 |

标题trim后1–100字，日期严格有效 `YYYY-MM-DD`（0001–9999年），可选时间仅 `HH:mm` 或null（全天），时间只表示开始日时刻；说明纯文本≤1000字（创建省略为空，更新省略保留）。不推断结束时刻，不生成TODO；拒绝未知字段和客户端归属/game/event字段。版本为非负Long且小于Long.MAX_VALUE。起止日期成对，结束不得早于开始，闭区间允许跨月/跨年/闰日及长跨度；混用旧date时必须等于start_date。旧date请求不能修改已有多日事项，返回422保留原记录，防止旧客户端压平跨度。响应含 `id/account_id/game/title/start_date/end_date/date/time/note/version/created_at/updated_at/time_zone`（date为开始日兼容别名），`time_zone=Asia/Shanghai`，不返回用户ID。

创建/修改/删除在既有 Mongo 事务中先获取账号写fence；更新使用 `@Version`，删除使用版本条件，过期版本或事务竞争返回409 `private_item_conflict`。账号重命名不影响事项；存在事项时变更游戏409 `calendar_private_game_locked`。账号删除在原事务级联删除私人事项，后续失败整体回滚，与创建共享账号fence避免孤儿记录。公开数据、管理员活动维护权限和订阅TODO结构/历史字段均不变。

最小定向验证（Mongo仅使用TestMongo自有临时容器，配置到可用Docker daemon，不连接开发/生产库）：

```bash
./gradlew test --tests '*CalendarPrivateItemServiceTest' --tests '*CalendarPrivateItemControllerContractTest' --tests '*ActivityCalendarSecurityTest' --tests '*SubAccountServiceTest' --tests '*ActivityCalendarSubscriptionServiceTest' --tests '*ActivityCalendarSubscriptionControllerContractTest' --console=plain
./gradlew integrationTest --tests '*CalendarPrivateItemMongoTest' --tests '*ActivityCalendarSubscriptionMongoTest' --console=plain
```

覆盖HTTP/JWT新旧契约、跨用户/账号CRUD、起止范围/闰日/长度校验、旧文档无回写读取和保留内容的更新、跨月跨年重叠/相邻窗口/长区间、真实持久化与唯一索引、CAS并发更新/删除、事务回滚、重命名/删除级联与删除竞争。升级时先兼容后端再前端；本次不部署、不重启服务。实际结果见根任务 `10-10-activity-calendar-private-items/verification.md`。

日期属性转换器同时处理空值查询条件。带 from 的范围查询包含 `endDate=null`（含缺失字段）的持续活动，ISO日期字符串保持不变；无数据库 QueryMapper 回归覆盖真实查询映射路径。
