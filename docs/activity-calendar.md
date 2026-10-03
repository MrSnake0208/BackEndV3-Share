# 活动日历 API（Batch 01 + 02）

仅实现手工目录与招募卡池聚合；没有活动日历页面、物理删除或导入接口。

## 公开查询

`GET /v1/activity-calendar` 无需登录。可选参数：

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
