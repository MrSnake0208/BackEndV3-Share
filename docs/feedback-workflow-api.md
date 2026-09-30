# 反馈工单流转接口

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
