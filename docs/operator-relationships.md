# 密探信赖与传闻

首版入口位于密探名册内。记录独立存储于 `operator_relationships`，不改变练度导入 v5、库存、成就或领取状态。当前两款游戏的奖励目录均为空：允许手动记录，前端显示资料待核准，不计算虚构收益。

## 接口

接口使用现有 JWT 身份，返回 `ApiResult` 包装及 `Cache-Control: no-store`。

| 请求 | 用途 |
| --- | --- |
| `GET /v1/operator/relationships/catalog?game=如鸢` | 返回 `game/version/operators`；游戏仅支持如鸢、代号鸢 |
| `GET /v1/operator/relationships?account_id=...` | 返回当前用户所属账号记录；空记录不会创建文档 |
| `PATCH /v1/operator/relationships?account_id=...` | 按字段合并，一次请求原子保存 |

PATCH 必须且仅包含 `expected_revision`、`catalog_version`、`entries`。例如下列密探 ID 仅作示意，调用时须使用当前游戏目录真实 ID：

```json
{"expected_revision":0,"catalog_version":"relationships-2026-10-22-empty","entries":{"operator_id":{"trust":0,"rumors":null}}}
```

未传字段保持原值，`null` 清除记录，`0` 明确记录为零；不接受字符串、负数、小数或非安全整数。批次最多 1000 位密探、1 MiB，保存后总记录最多 1000 位。已知上限会校验；上限未知仅做类型校验。共享信赖归并到规则明确指定的主体，传闻按卡独立；同批共享信赖值冲突时整批拒绝。

响应包含 `account_id/game/revision/catalog_version/entries/updated_at`。无变化不增加版本。版本或规则变化返回 409，前端需重新读取后核对；非法数据 422，非本人或不存在的账号 404。账号写围栏与版本 CAS 在 Mongo 事务中执行；删除账号清理记录，有实质记录的账号禁止切换游戏。持久化只使用现有 hub 数据库及事务管理器。

## 维护奖励资料

静态目录：`src/main/resources/operator/relationships.json`。修改规则须更新 `version`，区分两款游戏和每张卡，不能按稀有度猜测传闻总数。

每位密探定义 `trust_owner_id`、`trust`、`rumors`。未知领域为 `null`；已收录领域必须包含 `max/complete/source/verified_on/milestones`，档位为 `{at,rewards:[{item_key,name,quantity}]}`。阈值严格递增且不超过上限；奖励数量为正安全整数；日期为 ISO 日期；同一档位物品键不重复。共享主体必须存在、指向自身，关联卡的信赖规则必须完全相同。

只有人工核准完整且 `complete=true` 的领域才纳入奖励计算。物品按稳定 `item_key` 汇总，达标档位不再计入未解锁奖励；不能据此判断是否已领取。禁止把测试数据填入正式目录。

首次补充或变更共享主体前，须核对已有独立记录并设计冲突处理，先备份再迁移；本版没有自动迁移既有共享关系的功能。不能直接改映射后宣称历史数据已归并。

## 定向验证

`OperatorRelationshipServiceTest` 覆盖字段语义、校验、共享、版本与纯读取；`OperatorRelationshipControllerTest` 覆盖身份传递和响应；`OperatorRelationshipMongoTest` 使用 TestMongo 独立可抛弃容器验证隔离、CAS 与回滚；`SubAccountServiceTest` 覆盖账号生命周期。
