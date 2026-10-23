# 星石本地 OCR 初版

MaaYuan 本机读取 720×1280 / DPI320 截图，仅上传 `myshare-star-scan@1` JSON。后端将原报告存入 `star_scan_records`，上传不写 StarState。契约及完整字段见前端仓库 `docs/developers/api-star.md`。

- 新 scope `star:scan:write` 只提供绑定账号读取和报告暂存；不能确认导入。旧图片 scope 不自动升级。
- JWT 核对接口从登录身份和 `account_id` 检查所有权；报告、状态、佩戴同时按账号隔离。账号删除级联删除报告。
- 原报告不可变；同批相同内容幂等，不同内容冲突。已放弃报告不能再应用。
- 用户指定旧实例映射；服务端读取旧计划、佩戴、突破并保留合法值，不接收客户端提交这些关联。移除必须明确确认。
- 应用检查 generation、revision 和 loadout revision，在 Mongo 事务内写当前档案、修剪佩戴、保存恢复点和回执。重复命令返回同一回执。
- 发布顺序：后端 → 网页 → MaaYuan。沿用旧图片接口兼容旧客户端；不存在自动把截图上传切换成云端 OCR 的兜底。

定向验证：`OpenApiStarScanControllerContractTest`、`SubAccountServiceTest`、`StarStateMongoTransactionTest`。事务用 TestMongo 专属临时副本集，不连接开发/生产库。
