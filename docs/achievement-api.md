# 成就档案 v1

## 当前资料与开放状态

运行时权威目录为 Hub MongoDB 的 `achievement_catalogs`，每游戏一个文档。公共接口逐次读取 `published`，不会读取草稿或进程缓存；响应 `Cache-Control: no-store`，前端不持久缓存目录、PWA 不缓存业务 API。管理员发布成功后玩家下次刷新可读取新版本，无需重启后端。

`src/main/resources/achievement/catalog.json` 仅为缺失数据库文档的一次性种子/离线备份。目前种子代号鸢、如鸢均0条，不含测试 fixture。已有文档优先且不会覆盖；仅查库确认缺失时严格校验并 insert，并发初始化重复键重读获胜者。数据库/资源/校验异常直接报错，不静默降级空目录。初始化按游戏独立执行，不读取或写入个人档案。

日常资料通过网站「管理工作台 → 内容维护 → 成就目录管理」维护，详细接口及操作见 [成就目录管理](achievement-catalog-admin.md)。游戏和系统生成的稳定 ID 不可编辑；成就名称和有效分类必填，条件、奖励、来源、核验日期等选填，不要求逐条核验后才发布。只有明确累计口径才填写 progress_kind（integer/number）与 target，unit 选填。公开 data_status 自动区分 empty/partial，coverage 可自动生成；complete 仅由管理员明确选择，不按条目数量推断完整程度。

下架条目不删个人记录，仍可完整备份，但不计入当前有效目录统计。前端玩家入口仍受原统一 feature flag 控制；本模块独立于羁绊助手。

## 接口与保存约束

沿用 `ApiResult` 包装、snake_case 字段和现有异常格式。下列个人接口要求网站 JWT、现有 Beta 资格和账号归属；公开目录匿名可读，所有响应 `Cache-Control: no-store`；不开放外部 token/share scope。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| GET /v1/achievements/catalog | game=代号鸢 或 如鸢 | 公开目录、版本及覆盖范围 |
| GET /v1/achievements/archive | account_id | account_id、game、revision、records |
| PATCH /v1/achievements/records/{id} | account_id、expected_revision 与个人字段放在同一对象 | 更新后的完整档案 |
| POST /v1/achievements/batch | account_id、expected_revision、records | 更新后的完整档案；1–50 个不同 ID |
| GET /v1/achievements/export | account_id | 当前账号完整 JSON 备份 |
| POST /v1/achievements/import/preview | account_id、strategy、document | 逐条预览、数量、revision、catalog_version、preview_token |
| POST /v1/achievements/import/confirm | 上述输入，加 expected_revision、catalog_version、preview_token、skip_unknown | 原子写入后的完整档案 |

单条/批量 record 是部分更新：achievement_id 与 status/favorite/note/progress/completed_on 中至少一项；单条 ID 在路径中。省略字段保留，禁止客户端指定归属或来源。status 为 pending（待核对）、incomplete（确认未完成）、completed（已完成）；没有记录默认 pending。关注、备注和数值独立，不自动确认完成；非 completed 状态清除完成日期，不伪造今天为实际日期。备注纯文本最多500字符；进度非负、有限且不超过 JavaScript 安全整数上限，整数口径拒绝小数，无进度口径拒绝填写数值。

单条示例：`{"account_id":"已拥有的账号ID","expected_revision":0,"status":"completed"}`；批量示例：`{"account_id":"已拥有的账号ID","expected_revision":0,"records":[{"achievement_id":"目录中的稳定ID","favorite":true}]}`。示例中的占位 ID 不是生产成就。

档案按 userId/accountId 隔离，revision 初始0，成功内容变化才递增；无变化不加版本。客户端须提交读取的 expected_revision，过期返回409。服务端更新 updated_at/revision/source；source 仅由服务路径赋 manual/import。state_source/state_updated_at 和 progress_source/progress_updated_at 独立，改备注不会重新确认状态/进度。公开目录不含私有字段。

Mongo 文档使用 @Version CAS，写入事务沿用账号 fence；账号删改与迟到写入竞争会冲突，不复活孤儿。删除账号级联删除档案及本模块预览缓存；有实质记录禁止修改所属游戏，没有内容的空壳可清理后改游戏。浏览不创建占位档案。

## JSON v1 与导入冲突

备份格式包含 format=`yuanhub-achievement`、schema_version=1、game、catalog_version、exported_at、source_account.name（仅提示）、records；记录包含稳定 achievement_id、三态、关注、备注、绝对进度、可选完成日期和服务器时间/版本/来源。无 token、用户 ID 或源账号权限凭据。导出当前账号全部记录，包括历史目录记录，不受筛选影响。

最多1000条、1MiB UTF-8 JSON；HTTP 导入封装额外允许4KiB，超限413。保存时同时约束完整备份大小，确保本模块导出可重新读入。损坏 JSON、不支持的 schema、非法类型、跨游戏、重复 ID、数值或日期非法均不会部分写入。备份中的来源和时间不可伪造成可信第三方来源，实际变更由服务端标为 import。

必须先预览：逐条返回 add/same/conflict/unknown/invalid，及最终 affected 数。默认 keep_current 完整保留已有实质冲突记录；明确 use_file 才采用文件提供的字段。空备注不擦除现有备注，缺项不删除，绝对进度不累加，相同内容重复导入不增加版本。目录版本不同可按稳定 ID 核对；未知 ID 需 skip_unknown=true 明确跳过，不生成条目，历史备份仍保留在原账号。任一 invalid 阻止整批确认。

preview_token 绑定用户、目标账号、游戏、当前档案版本、目录版本、策略及文件内容摘要，有效10分钟；确认重新完整校验。换文件/策略/目标、档案或目录变动均需重新预览。预览不写数据库；内存缓存每用户/账号一份、总计最多512份，重启失效。当前单实例模式，多实例需粘性路由或共享预览存储；不会默默跨实例确认。

## 后续羁绊边界

未来须先验证账号映射、授权和来源，再映射稳定 ID，不按名称匹配。外部观察与人工确认分别保存来源，旧数据/缺项不能降级完成；总量不能作为增量重复相加。达标先提示用户，成就完成不能反向解锁羁绊。本轮没有同步按钮、适配器或第三方权限。

## 定向检查

`AchievementCatalogAdminContractTest` 覆盖管理员权限/严格输入/初始化；`AchievementCatalogMongoTest` 使用隔离 Mongo 验证发布、草稿、CAS、回滚与个人记录兼容。`AchievementContractTest` 覆盖协议、严格输入及公开/私有响应；`AchievementMongoTest` 使用自有可抛弃 Mongo 副本集覆盖隔离、CAS、导入往返/原子性与账号生命周期竞争，不连接开发库。

```sh
./gradlew test --tests 'com.lhs.share.hub.service.achievement.AchievementContractTest' integrationTest --tests 'com.lhs.share.hub.service.achievement.AchievementMongoTest'
```
