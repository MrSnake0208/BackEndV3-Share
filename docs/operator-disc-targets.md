# 双命盘目标保存与交换

每个 `operator_current.entries[operatorId].discLoadouts` 组合增加 `status`（`unmarked` / `reroll` / `perfect`）及 `targetDiscs`。缺失字段由实体默认值兼容，不需要迁移。它们是用户意图，`discs` 是实际词条；旧字段 `entries.discs` 仍只镜像第一套实际词条。

## PATCH / current

沿用密探 current 的账号归属校验、`expected_revision`、CAS 和 correction 记录，不新增接口或存储集合。JSON 使用 `target_discs`：

```json
{
  "expected_revision": 7,
  "reason": "manual_correction",
  "disc_loadouts": [
    {"id":"disc_1","name":"输出","discs":[{"ot_name":"实际词条"}],"status":"reroll","target_discs":[{"ot_name":"目标词条"}]},
    {"id":"disc_2","name":"生存","discs":[],"status":"perfect","target_discs":[]}
  ]
}
```

状态必须是枚举值；目标允许 0–3 个，必须属于该密探图鉴且不重复。显式 null、未知词条、重复和超量会返回 `invalid_disc_loadout`，不会写入。修改状态不会自动清空目标，词条相同也不会自动标记完美。

组合身份按数组顺序（命盘一/二）判断。采集或旧客户端未提交 `status` / `target_discs` 时，保留对应序号已有值，组合 id 改变不清除目标。列表截短/清空时，遗漏组合的实际词条清空；有标记或目标的槽位仍保留。显式空 `target_discs` 只清空该套目标。v2 导入仍只更新第一套实际词条，保留其目标和第二套。

## 备份与边界

v3/v4/v5 schema 可选扩展这两个字段，历史 JSON 无需修改；新备份显式导出两个字段，恢复通过原 PATCH 校验和 correction 重放。v2 格式不承载目标，需使用新版备份。公开 BOX 分享 DTO、作业筛选、战斗输入只读取实际词条，目标不会被公开或用于判定已拥有的命盘。

前后端需配套更新。未修改数据库事务、索引或账号身份语义；定向单元/契约与无连接 BSON converter 检查即可覆盖新增字段，数据库和浏览器全面回归由用户另行安排。
