# 养成规划云端 API

本接口按 YuanHub 当前 `OperatorGrowthTracker.vue`、`operatorTrainingPlans.js`、`cultivationPlanner.js`、`fixedPlannerSchedule.js` 实现编写。早期培养计划文档不作为本接口的行为定义。

## 范围与持久化

培养工作区、每个培养清单的体力日程分别保存、分别维护 revision。推荐、缺口、目标天数试算继续由前端计算。保存预测不会更新真实库存、奖励流水、当前密探练度；预测结果不是后端执行提升的凭据。

MongoDB 新增 `operator_training_workspace`、`operator_stamina_schedule`、`operator_planner_import`。前两个以 owner / owner+plan 唯一，最后一个以 owner+migration 唯一。owner 包含 JWT 用户及其子账号。正文经验证后保存为 JSON 字符串，以完整保留模拟器结构、动态材料键与数值；不按预测字段建查询索引。普通 GET 不创建文档。

不改动已有库存、密探 v2/v3 导入导出、默认 growth-targets、annotations 和 favorites 数据。升级部署会由现有 Spring Data 配置创建新集合索引；单文档首写还使用确定性 `_id` 防止并发重复。回滚代码可保留这三个集合，旧版本忽略它们；无需变更已有业务数据。

全部端点使用 JWT、`account_id` 查询参数及既有 ApiResult 包装。不存在或不可访问的子账号返回 404 `account_not_found`。接口不接受客户端 user_id。

## 工作区

`GET /v1/operator/training-workspace` 返回下列正文，额外包含 `account_id,revision,updated_at`。不存在时 revision=0、updated_at=null。

`PUT /v1/operator/training-workspace` 接收完整正文及 `expected_revision`：

```json
{
  "schema_version": 1,
  "expected_revision": 0,
  "active_plan_id": "favorites",
  "training_levels": {"fh":12,"ds":12,"yy":12,"experience":8},
  "plans": [
    {"id":"favorites","name":"特别关注","source":"favorites","operator_ids":[],"excluded_operator_ids":[],"targets":{}},
    {
      "id":"4d4e3763-2b56-4dda-9a3f-a79d4634e74b",
      "name":"主队培养","source":"custom",
      "operator_ids":["char_001_yangxiu"],"excluded_operator_ids":[],
      "targets":{"char_001_yangxiu":{"level":80,"elite":13,"star_level":19}}
    }
  ]
}
```

默认清单固定 id/source=favorites，在存在时必须唯一且 targets 为空；有效成员由实时 favorites 加入 operator_ids、再扣除 excluded_operator_ids 得到。自建清单使用稳定 UUID、source=custom。工作区可以不含任何清单，此时 `plans=[]` 且 `active_plan_id=null`；存在清单时 `active_plan_id` 必须指向其中一张。移出成员的目标可保留以供重新加入。名称去首尾空白后 1..40 字，成员 ID 不可重复，目标与成员必须是已知密探。四类最高通关层数范围 1..12 / 1..8，目标范围 level=0..100、elite=0..17、star_level=0..31，且 elite 不得超过 `min(17,max(0,floor(level/5)-3))`。非法组合直接拒绝，不截断或改写用户提交的数据；客户端仍以当前档案进度为显示下限。

完整快照条件更新；成功 revision 加 1，过期版本返回 409 `training_workspace_revision_conflict`，不自动重试覆盖。删除清单通过工作区 PUT，关联日程暂留但不可从该清单 API 访问；当前页面撤销恢复原 UUID 后可恢复日程。子账号删除时一并清理工作区、所有日程和导入回执。

## 体力日程

`GET/PUT /v1/operator/training-plans/{planId}/stamina-schedule`。所属清单不存在返回 404 `training_plan_not_found`。

```json
{
  "schema_version":1,"rules_version":13,"timezone":"Asia/Shanghai",
  "expected_revision":0,"strategy":"overall","agent_order":[],
  "preferences":{"luoyang":0,"shouchun":0,"purchase_count":0},
  "manual_plans":{},"schedule":null
}
```

GET/PUT 响应额外含 `account_id,plan_id,revision,updated_at`。strategy 为 overall/priority；派遣偏好 0..4，购买偏好 0..8。timezone 使用 IANA 时区。用户首次自定义后，schedule 是当前前端完整固定快照，字段按 snake_case 转换：

- `version,start_date,baseline_date,context,history,result`。
- context：`date,initial_state,required_state,stock,goals,levels,strategy,agent_order,preferences`。
- 每日预测保留 `date,start,end,recommended,planned,manual,errors,warnings,sources,yield,planned_yield,used,surplus,totals,progress_rows` 以及存在时的 `index,paused,legacy`。
- result：`timeline,status,eta_days,last_progress_day,remaining,blocked`。
- 资源键（如 `shuijing`、`__xp__`）、密探 ID、日期键不改写。
- gains：来源 id/label/kind/value 与当前前端保存的可选展示字段。
- spends：支出 id/label/kind/value/cost_per/yield，历练含 group_id/stage_level，自定义可含 name/custom。

用户编辑中的次数小数、超支等不可执行安排可以保存为草稿，不静默截断。预测状态为 invalid/paused 的日子必须 end=start 且实际 yield/used/surplus 为零；planned_yield 可用于展示原拟产出。后端验证结构与此持久化约束，不替代前端重算固定渠道掉落。

GET 不因日期、当前库存、练度或规则变化重算。PUT 不允许清空已有固定快照、不允许更改固定快照时区；按保存时区计算业务日，并以当地 05:00 为切换点，过去业务日必须完整保持。前端显式更新可从当前库存重新生成今天及以后；历史预测及未来自定义安排由前端完整提交。允许保留周期以外的自定义日期。

过期 revision 返回 409 `stamina_schedule_revision_conflict`。结构错误返回 422 `invalid_training_workspace` 和 `field_path`；不支持的外层版本返回 422 `unsupported_training_workspace_version`。

## 移除与毕业

`POST /v1/operator/training-plans/{planId}/members/{operatorId}/remove`：

```json
{"expected_revision":3,"graduate":true,"expected_annotation_revision":2}
```

expected_revision 指工作区。graduate=false 时不需要 annotation revision。graduate=true 时在同一个 Hub Mongo transaction 内更新成员和 annotation.growth_state=graduated，保留备注、favorites、所有目标。任一步冲突都会回滚；返回 `{workspace,annotation?}`。当前页面撤销仅恢复清单，不回退毕业标注。

## 本机导入

`POST /v1/operator/training-workspace/import-local`：

```json
{
  "migration_id":"persistent-client-generated-id",
  "workspace":{"schema_version":1,"expected_revision":0,"active_plan_id":"favorites","training_levels":{"fh":12,"ds":12,"yy":12,"experience":8},"plans":[{"id":"favorites","name":"特别关注","source":"favorites","operator_ids":[],"excluded_operator_ids":[],"targets":{}}]},
  "schedules":{}
}
```

schedules 是 planId → 日程 PUT 正文，包含各自 expected_revision。前端先比较本机和云端、提供已选择的完整快照；服务端不隐式合并。默认云端 growth-targets 不在此迁移中重复导入。

工作区、日程和回执在同一事务提交。相同 owner+migration_id 且相同请求重试，在 revision 检查前返回原 `{migration_id,imported_at,workspace,schedules}`；相同 ID 不同内容返回 409 `training_workspace_migration_conflict`。请求正文直接结构比较，不新增哈希。

## 事件与验证

成功提交后通过现有账号 SSE 发布 `operator_training_workspace`、`operator_stamina_schedule`；组合毕业另发 `operator_annotation`。data 含 account_id/revision 和日程的 plan_id。事件用于触发重新读取，不携带敏感快照；重连仍需要 GET 补齐状态。

单元及 HTTP 契约测试：

```bash
./gradlew test --tests '*OperatorPlanner*' --tests '*SubAccountServiceTest'
```

测试资源 `src/test/resources/operator-planner/frontend-fixed-schedule.json` 由当前前端模拟器直接生成，用于固定日程往返验证。可用本地 replica set 验证实际并发 CAS 和跨集合回滚：

```bash
PLANNER_TEST_MONGO_URI='mongodb://127.0.0.1:27017/?replicaSet=rs0&serverSelectionTimeoutMS=3000' \
  ./gradlew test --tests '*OperatorPlannerMongoTest'
```

集成测试仅创建并删除自己的随机 `planner_test_*` 数据库，不读取或修改应用数据库。
