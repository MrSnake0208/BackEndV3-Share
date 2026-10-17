# 反馈工单流转接口

## 附件数量

`POST /v1/reports` 和 `POST /v1/reports/{id}/messages` 的 `media_ids` 允许 0–10 个，图片与普通文件共用额度；用户追加与管理员回复使用同一上限。超过 10 个返回业务 `status_code=400`。媒体存在、上传者归属、未删除和 ID 不重复的约束继续生效。单个文件上传大小与图片压缩规则不变。

## 类型与活动日历板块

`type` 可选 `BUG`（问题反馈）、`EXPERIENCE`（体验优化）、`FEATURE`（功能建议）、`CONTENT`（内容问题）、`ACCOUNT`（账号问题）、`REPORT`（举报）、`OTHER`（其他）；与反馈板块 `category` 独立。旧 `type=FEEDBACK`、`category=类型`、`area=板块` 请求继续兼容；不迁移历史工单或将 OTHER 自动改成日历。未知的新建类型仍返回业务 400。

`GET /v1/reports/access` 的 `available_categories` / `available_areas` / `available_work_areas` 使用同一动态目录中的可见板块；`category_catalog` 返回完整目录（含 `hidden`，旧数据默认 `false`），供板块管理和历史名称展示。缺少日历的空库或旧目录提供 `CALENDAR` /「活动日历」且只出现一次；已有同名自定义板块复用其 key，并通过可选 `builtin_key=CALENDAR` 标识语义。该标识只供来源预填，提交、筛选、授权和流转必须使用返回的实际 key，不能把别名当成授权。自定义日历改名后标识仍保留。目录读取不写库；后续管理员目录编辑沿用原乐观锁持久化补齐结果。

普通用户可选日历提交自己的工单，但不因此获得管理权限。非超级管理员必须显式授予实际日历 key 的运营/程序范围；活动日历内容维护权限不授予反馈查看或操作权。程序岗仍仅能在交接或交回条件满足时查看，不能代替运营控制。超级管理员延续已有全部目录范围语义。未修改授权集合、历史归属、消息与未读字段。

公开列表/详情增加可选 `category`、`category_label`，仅用于展示目录板块；公开查询仍要求 `visibility=PUBLIC`。不暴露内部负责人、原始正文、消息、附件、诊断或私人日程。分类名称每批查询一次，避免列表逐条读取目录。

日历入口 `/feedback?new=1&category=CALENDAR` 默认问题反馈；招募线索入口额外携带 `type=CONTENT`。前端等待目录后解析到实际 key，不从链接读取正文、账号或日程。

`POST /v1/reports` 接受 `public_consent`，默认 false，与 `client_info_consent` 独立。默认私下提交无需标题；`public_consent=true` 时 `title`（最多 120 字符）必填，空标题返回业务 400，且不读取附件或保存工单。授权不会自动发布，创建时可见性仍为 PRIVATE。

用户和管理员的私有详情返回 `public_consent`。缺少字段的旧请求/旧文档视为未授权，不自动补授权。管理员发布接口 `PATCH /v1/admin/feedback/{id}/publish` 先校验原有管理权限，再要求工单 `public_consent=true`，未授权返回业务 403 且不写公开字段。管理员不能代替用户修改授权；已经公开的历史内容保持可见，但未授权不能重新发布或通过发布接口修改公开摘要，仍可取消公开。公开 DTO 只返回整理后的公开字段，不暴露授权、原始正文、附件或账号信息。

运营在工单详情中执行接单、转交、转程序和调整负责板块。工单状态由详情接口的 `workflow_stage` 返回；`PROCESSING` 表示已接单，重新打开详情不需要再次接单。

`GET /v1/admin/feedback/{id}/assignees` 返回当前工单可转交的运营候选人：`[{"id":"user-id","user_name":"姓名"}]`。请求者须能控制该处理中工单。结果只包含当前负责板块有运营权限的已激活用户及已激活超级管理员，排除当前负责人。无候选人时返回空数组；权限不足返回 403，工单不可转交返回 409。提交转交时仍由 `POST /v1/admin/feedback/{id}/assign` 重新校验目标权限。

`POST /v1/admin/feedback/{id}/handoff` 使用工单当前 `work_area` 转程序；仅 `POST /v1/admin/feedback/{id}/work-area` 改变负责板块。

流转通知复用既有 `title`/`body`：标题保存当次操作人、反馈标题（无标题时取正文前 40 字）及交接关系；正文分别展示反馈原文前 100 字和交接说明前 100 字。昵称缺失时显示用户 ID。转交与接手使用不同文案，原负责人、新负责人和操作人按用户 ID 去重收件。转程序标题列明目标板块及当次程序接收人员，收件人包括这些程序人员、运营负责人和操作人；这是板块交接，不代表指定个人程序负责人。

程序交回通知程序操作人和运营负责人；运营撤回使用“撤回程序交接”文案，通知操作人及运营负责人，按用户 ID 去重。沿用现有通知 kind、权限和失效清理规则。流转记录的 `note` 同时保存当次交接标题和完整说明，后续转交或昵称变化不改写这份快照。状态通知另附反馈原文前 100 字，新反馈及回复继续保留原有正文预览。已有通知与记录不回填，也不使用当前负责人猜测历史交接对象。

`GET /v1/admin/feedback/queue` 接受 `queue`（默认 `UNASSIGNED`）、`page`（默认 1）、`pageSize`（默认 20，1–100）、`workArea`、`type`、`q`、`sortBy`（默认 `updatedAt`，仅 `createdAt`/`updatedAt`）和 `sortOrder`（默认 `desc`，仅 `asc`/`desc`，不区分大小写）。无效队列或排序返回业务 400。筛选、授权和队列条件在数据库分页前生效，返回的 `total` 是全部匹配数。管理员界面可用相同筛选、`page=1&pageSize=1` 获取每个可见队列数量。

队列包括 `UNASSIGNED`、`MINE`、`NEEDS_REPLY`、`DEV`、`RETURNED`、`CLOSED`、`ALL`。新增 `NEEDS_REPLY` 限制开放、未合并、`PROCESSING`、当前管理员是运营负责人且处于其运营板块的工单；按消息中的 `REPORTER`/`ADMIN` 顺序判断最后有效沟通来自用户，与 `FeedbackWorkflow.needsReply` 一致，不依赖历史预览字段 `lastMessageSender`。程序岗位范围不授予此队列的运营权限。

`RETURNED` 限制开放、未合并、`PROCESSING` 且有 `developerReturnedAt`，同时支持授权运营和程序板块，仍受原有可读范围限制。其他板块、已结案或未交回工单不会返回；本次不改变写入状态机、岗位模型和兼容管理授权。


## 2026-10-16：取消可见反馈类型，保留底层兼容

普通反馈表单仍向 `POST /v1/reports` 发送显式 `type=OTHER` 和动态 `category`，私下提交无需标题；`public_consent=true` 仍要求非空标题。客户端信息同意独立，默认 false。请求/响应字段、全部旧类型枚举、FEEDBACK 解析及内部类型筛选接口保留；不迁移历史类型或授权。前端工作台与广场不再发送类型筛选，公开编辑不再调用类型修改接口。

举报（`REPORT`，含旧 `FEEDBACK/category=REPORT`）始终排除公开访问：发布返回业务 403，公开详情/支持/取消支持返回业务 404，公开列表、相似召回、完成版本反查在 Mongo 查询层过滤，total 不包含举报。公开合并跳转不暴露举报目标。举报不允许通过类型更新改成普通工单，也不能与普通工单相互合并；已公开普通工单不能改为 REPORT。发布与类型写入的原子条件同样校验此边界，避免并发绕过。举报仍可由原有授权方查看、回复、流转、结案或取消误公开。

七队列定义、权限与统计语义不变。前端将 UNASSIGNED/MINE/NEEDS_REPLY/DEV 作为主入口，RETURNED/CLOSED/ALL 作为次级入口；数量不可直接相加。RETURNED 表示曾交回运营，不能当作严格的未验收数量。全管理范围已读不受当前筛选限制。

## 板块排序

`PUT /v1/admin/feedback-categories/order` 接受 `{"keys":["OTHER","CALENDAR"],"expected_keys":["CALENDAR","OTHER"]}`（示例仅含两个板块，实际必须包含完整 `category_catalog` 的全部 key，包括隐藏项）。`keys` 是新顺序，`expected_keys` 是客户端读取时的原顺序；响应为按新顺序排列的完整板块数组。权限沿用 `admin:feedback_access:manage`，无权限返回业务 403；重复、遗漏或未知 key 返回 400；原顺序过期或目录乐观锁冲突返回 409，客户端刷新后重试。

数组顺序直接持久化到现有目录，不新增字段或迁移数据。排序保留 key、名称、builtin_key、hidden、历史工单和授权。隐藏项仍参与排序，可见选项仅过滤隐藏项并保留相对顺序；新板块仍追加末尾。相同顺序不写库、不重复审计。实际变更在 hub 事务内记录 `FEEDBACK_CATEGORY_REORDERED`，`feedback_category_order` 前后快照为板块名称数组，供审计页面按当时名称显示。

用户执行下方已有目录测试命令即可覆盖排序新增用例。

## 板块隐藏与恢复

`PUT /v1/admin/feedback-categories/{key}/visibility` 接受 `{"hidden":true}` 隐藏，`{"hidden":false}` 恢复；响应为含 `key`、`label`、`builtin_key`、`hidden` 的目录项。沿用 `admin:feedback_access:manage` 权限和目录乐观锁：无权限、板块不存在、并发目录变更分别返回业务 403、404、409。重复设置不写库、不重复审计；变更使用 `FEEDBACK_CATEGORY_VISIBILITY_UPDATED` 审计及 `feedback_category_hidden` 前后快照。

隐藏适用于内置和自定义板块。前端用户提交/筛选、管理员筛选/调整板块/授权选择只用可见项；板块管理保留隐藏项和恢复入口。所有板块隐藏时可选目录为空，仍能查看、处理历史工单。历史名称、key、已有授权和流转权限保留；隐藏不是撤销权限，也不改变旧客户端/直接 API 的板块参数接受范围。隐藏后改名不恢复显示，自定义日历仍沿用原 key 和 builtin_key，不补建另一个可见日历。

验证（用户执行，Mongo 仅使用 TestMongo 自有一次性容器）：

```bash
./gradlew test --tests '*FeedbackCategoryServiceTest' --tests '*FeedbackAccessServiceTest' --tests '*FeedbackCategoryControllerContractTest' --console=plain
./gradlew integrationTest --tests '*FeedbackCategoryMongoTest' --console=plain
```
