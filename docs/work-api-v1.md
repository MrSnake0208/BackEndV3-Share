# Work API v1

YuanHub Work 第一阶段从 `MaaBackend.maa_copilot` 实时只读转换公开且未删除的 MaaYuan 作业，不复制或修改源数据。执行语义只读取 `content` 原始 JSON；数据库中的简化动作字段不参与转换。

## 接口

### `GET /v1/works?page=1&limit=20`

返回 1-based 分页结果：`page`、`limit`、`total`、`has_next`、`items`。`limit` 范围为 `1..100`。

### `GET /v1/works/{id}`

返回社区元数据、可靠匹配时的 Level 摘要、`conversion`、可形成合法协议时的 `work`，以及未经改写的 `source.raw_content`。不存在、非公开或已删除的作业统一返回 HTTP 404。

`conversion.status` 为 `exact`、`partial` 或 `unsupported`。无法确定的 Pipeline 节点会出现在 `conversion.issues`，并携带原 JSON path；不会被静默丢弃。

### `GET /v1/works/{id}/compatibility?to=MAAYUAN|YUANASSIST`

MAAYUAN 仅在全部语义 `exact` 时返回 `target_document.round_actions`。YUANASSIST 第一阶段只返回逐动作兼容性分析，不返回伪可执行文档。

所有成功响应使用项目统一 `ApiResult`，字段按 snake_case 输出。非法分页或目标返回 HTTP 400。
