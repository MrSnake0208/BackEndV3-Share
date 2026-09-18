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

## 第一阶段未实现或未完整支持

以下项目是当前明确保留的后续工作。调用方不得因接口返回了 `work`，就假定所有目标平台都能直接执行；应始终检查对应目标的 `status` 和 `issues`。

### 通用能力

- 未实现 Work 的上传、编辑、删除、审核、持久化或独立索引；当前只实时读取 Legacy Source。
- 未实现作业搜索、标签筛选、游戏筛选、关卡筛选和自定义排序；列表目前只有基础分页。
- 未提供通用导出接口。兼容性接口只负责分析，并在满足当前 Adapter 的无损条件时返回阶段性目标文档。
- 未使用 JSON Schema 对每次响应做运行时二次校验；合法性目前由类型模型、Parser 边界检查和契约测试保证。
- 缺失或冲突的游戏、标题、关卡名、回合信息不会被猜测补全，必要字段不足时 `work` 为 `null`。阵容不足五项时以 `null` 保留空槽位，超过五项时只保留协议允许的五槽位并报告 compatibility issue；详情始终保留 raw source。

### Legacy Parser

- 仅实现已验证的 MaaYuan-SiMing 标准节点、旧式回合图和当前 CustomAction。任意自定义 Pipeline 节点、未知分支或缺失 `custom_action` 的 Custom 节点尚无转换规则。
- 不把 MaaFramework 的 Click、Swipe、ROI、模板识别等底层实现反推为 Work 动作；只有已验证的 `text_doc`、CustomAction 和标准分支语义会进入协议。
- 未实现对未知 Pipeline 图的通用控制流求值。不能确认的节点会产生 `unsupported_source_node`，原始内容保留在 `source.raw_content`。
- 超出 Work Protocol v1 边界的数据，例如回合号不在 `1..50`、等待或延迟超限、标题或说明超长，不会被截断或静默修正。

### Level Catalog 关联

- 只支持稳定 ID、stage ID 或名称的唯一精确匹配；未实现模糊匹配、别名表和人工映射覆盖。
- 候选不唯一或证据不足时省略 `level_id`，不会为了关联成功而选择任意 Level。
- Level Catalog 只用于引用和导航信息补充，不会把 Level 数据复制进 Work，也不会用 Level 内容改写战斗动作。

### MAAYUAN Adapter

- 当前 `target_document` 是标准化 `round_actions`、延迟和 MaaYuan 扩展，不是完整 MaaFramework Pipeline；尚未接入 SiMing `ConfigGenerator` 生成最终 Pipeline。
- 仅 `exact` 时返回 `target_document`。`partial` 和 `unsupported` 不会输出可能被误执行的降级文档。
- `pause`、`auto_battle(false)`、暴击检测和非 `restart` 的检测失败行为尚无稳定映射。
- `operator_action(switch_form)` 目前只有目标槽位明确为吕布时可无损映射。
- 龙气仅支持“指定槽位 `>= 2`”，星检测仅支持橙、紫、蓝的“`>= 1`”；其他比较条件不支持。
- 当前 SiMing 共用 SP 与大招延迟；两者配置不一致时标记为 `partial`，不会静默选取其中一个。

### YUANASSIST Adapter

- 当前只实现兼容性分析和接口骨架，不生成 `scriptContent + instructions + config`，也不返回 `target_document`。
- 基础槽位动作、等待、暂停和切换目标只完成语义可表达性判断，尚未编译为 YuanAssist 原生指令。
- 自动战斗开关、关卡互动、吕布切形态、立即重开和退场检测暂无等价指令。
- 带槽位的龙气检测和蓝星检测不支持；橙星、紫星及其他检测即使语义可表达，也尚未实现失败后的自动导航。
- 未可靠关联 Level Catalog 时，任何依赖失败后重新进入关卡的检测都会报告 `restart_navigation_unavailable`。

## 后续补充原则

- 新增 Parser 映射前，必须先用真实 MaaYuan-SiMing 产物确认节点语义并添加回归样例。
- 新增 Adapter 能力时，应先扩充 compatibility 规则，再生成目标文档；不允许通过丢动作换取 `exact`。
- 完整导出应复用目标项目已有生成逻辑或经过对照测试的等价实现，避免维护第二套未经验证的 Pipeline 生成器。
