# 成就目录管理

## 数据与身份

Hub Mongo 的 `achievement_catalogs` 按 `_id=game` 存储 `revision`、`published`、`draft`、最近5个旧版本 `history`、永久 `identities`/`issuedIds`/`categoryIds`。不改变 `achievement_archives` 或其用户/账号隔离、事务、备份格式。分类通过稳定 category_id 关联，排序为公共分类顺序。

每次草稿/发布/回滚均使用 `_id + revision` 条件 replace；失败409，不自动合并或覆盖。发布时同文档替换整份 published 并保存上一版本，跨进程/重启有效；两款游戏版本独立。文档含历史最多12MiB，请求最多2MiB，最多100分类/1000成就。

新成就 ID 为 `yh_dhy_<uuid>` 或 `yh_ry_<uuid>`；分类为 `cat_<uuid>`。页面不可编辑游戏和 ID，服务拒绝未分配 ID。曾发布身份永久登记，退役不会回收 ID，历史从草稿移除后不能当新成就重新加入，恢复用历史回滚。不要把名称当作身份。

分类可更名、排序、停用；含有效成就时阻止停用/移除，先将这些成就迁至有效分类或退役。页面显示受影响数量。退役从玩家目录过滤，全部个人记录仍可导出；原有个人导入对已退役 ID 按 unknown 明确跳过，原账号历史记录不被删除。更名后原 ID 继续关联已有记录。

进度保护采用保守兼容规则：已发布的 progress_kind / unit 不可变，target 仅可增加（同样适用于回滚和退役后恢复）。这样不依赖与个人并发写入竞争的“先查后改”，不会裁剪、重置或更新个人记录。改变统计口径应另建成就；没有明确口径就保持全部进度字段为 null。历史目标降低会阻止回滚，不能借回滚绕过保护。

## 权限与 API

仅网站登录身份且具有 `achievement_catalog:write` 的账号可调用；默认 PLATFORM_ADMIN / SUPER_ADMIN 具有该权限，冻结账号无权限。所有管理读写均检查，匿名401、普通玩家403。不开放外部应用 token scope，不要求玩家 Beta 或玩家成就 feature flag。所有响应 no-store，沿用 ApiResult/snake_case 与 InventoryError。

基础路径 `/v1/admin/achievement-catalogs`，每个请求查询参数 `game=代号鸢` 或 `game=如鸢`。

| 方法与后缀 | 输入 | 行为 |
| --- | --- | --- |
| GET 空后缀 | game | revision、published、draft、history；无玩家记录/内部身份登记 |
| GET /export | game、scope=published/draft | 目录 JSON，包含管理字段，不含个人档案 |
| POST /categories | expected_revision、name | 系统发号，新建草稿分类 |
| POST /entries | expected_revision、category_id | 系统发号，新建空白草稿成就 |
| POST /draft | expected_revision、catalog | 保存完整草稿；允许条件/来源/奖励等暂缺，拒绝非法结构/ID/分类引用 |
| POST /preview | expected_revision、可选 restore_version | 新增/修改/退役明细、分类变更、名称/结构错误、进度规则变化 |
| POST /publish | expected_revision、可选 restore_version | 重新完整验证后原子发布；生成新版本、记录时间和管理员 |
| POST /discard | expected_revision | 明确放弃草稿，恢复为当前发布快照 |
| POST /import-preview | expected_revision、strategy、document | 逐条预览并生成15分钟 token；不写草稿 |
| POST /import-confirm | 上述字段 + preview_token | 校验预览绑定后合入草稿 |

`catalog` 的字段为 game/data_status/coverage/categories/entries/retired_ids/catalog_version。entries 兼容公共 AchievementEntry；categories 额外支持 sort/enabled，retired_ids 为管理字段；catalog_version 在保存时忽略，发布由系统生成。公开接口仍只返回原 AchievementCatalogResponse 字段，已禁用分类与退役条目不公开。

## 导入与导出

导出封装 `{"format":"yuanhub-achievement-catalog","schema_version":1,"catalog":{...}}`；导入也接受单游戏原公共 DTO JSON，不接受个人备份或跨游戏文件。初始种子数组需先取目标游戏的一份。

按 achievement_id 匹配已有草稿，绝不按同名合并。省略 achievement_id 表示新增，由系统发号；指定但未知/历史 ID 列为 unmatched，确认后跳过。新增分类使用文件中的 category_id 作临时引用，系统为其发号并同步条目的分类引用；已知分类 ID 保留。导出后再维护文件可继续匹配系统 ID；反复导入无 ID 的新条目会继续新建，先导出带 ID 的草稿再继续批量维护。

strategy 默认页面选择 keep；冲突保留，明确 update 才更新。无变化条目不重置旧元数据；新增和更新条目保留 unverified、空核验日期，不会伪造已核验状态，但这些兼容字段不再作为发布门槛。不推断缺失为删除，不导入文件的退役操作或完整收录声明；退役须在后台明确操作。任一非法条目或合并后无效分类引用阻止整批导入。预览 token 绑定管理员、游戏、修订、文件内容和策略；版本变化、15分钟到期、后端重启或跨实例需重新预览（第一版进程内预览，数据本身在 Mongo）。

新增导入最少提供目标 game、categories 和 entries。分类包含 category_id/name，成就包含 game/category_id/name；省略 achievement_id 由系统发号，condition 默认空文本、sort 默认文件顺序，其余资料均选填。来源和核验字段可省略，导入后管理员预览、确认发布即可供玩家使用。

## 发布与恢复

草稿可以不完整，玩家完全不可见。预览要求保存当前编辑；发布重新验证重复 ID、合法字段、有效分类和成就名称。条件、方法、奖励、来源、核验状态和日期不作为必填门槛；提供非空来源链接时仍要求合法 HTTPS。启用数值进度时目标须有效，单位选填；不支持数值进度的成就可只记录三态。后台不自动填写许可、日期或核验通过状态，也不抓取网页。

目录无有效条目自动标记 empty，非空默认 partial；coverage 可省略并自动生成说明。只有管理员明确选择 complete 才声明完整收录，条目数量不会推断完整程度。已有非空但仍标 empty 的草稿在预览/发布时自动修正，无需重新导入。页面展示预览后另点确认发布。服务按预览时修订确认，过期409。公共接口无进程缓存，后续刷新立即查新发布快照，无需改 JSON、构建或重启。玩家个人完成状态、进度、关注和备注继续独立保存。

历史回滚先对比指定版本与当前发布版，再确认发布为新版本，记录 restored_from，不删除当前版或个人记录。若有已保存但未发布的草稿，阻止回滚；先导出保留，再明确放弃。仅保留5个旧快照，永久 ID 登记不随历史裁剪。未知/过期历史版本不会回滚。

## 本地定向检查

```sh
./gradlew test --tests '*Achievement*ContractTest' --tests '*AdminAuthorizationServiceTest' integrationTest --tests '*AchievementCatalogMongoTest' --tests '*AchievementMongoTest'
```

集成测试只使用 `TestMongo` 自有 disposable 容器，不连接开发/生产 Mongo。fixture 仅在 test；禁止把测试成就作为真实资料发布。真实 UI/触控由用户人工验收，未授权不运行浏览器自动化。
