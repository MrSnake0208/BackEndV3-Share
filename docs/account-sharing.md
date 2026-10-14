# 账号公开分享契约

账号分享码由密探 BOX（`operator`）与星石背包（`star`）共用，两种授权独立。旧分享码缺少 scopes 时仅授权密探；旧密探撤销接口只关闭密探，账号级撤销和换码使两种旧链接失效。

- 管理接口：GET / PATCH / DELETE `/v1/accounts/share?account_id=...`，POST `/v1/accounts/share/regenerate?account_id=...`。要求 JWT、内测权限和账号归属；PATCH 请求为 `{ "scope": "star", "enabled": true }`。
- 匿名 GET `/v1/star/share/view/{code}` 返回分享码所属账号最新已保存 Inventory。条目只有 `kind/name/quality/level`，保留重复实例，不接受客户端指定 owner/account/generation，不公开计划、实例 ID、经验材料或佩戴关系；读取不修改状态。
- 尚未保存云端状态时 `has_state=false`、`updated_at=null`、`inventory=[]`；已保存空背包时 `has_state=true`。未开启分享时管理响应 `active=false`、`share_code=null`。旧密探分享响应的同名可空字段保留相同行为。
- OpenAPI 使用 3.1；`shareNullableSchemaCustomizer` 将以上分享响应的可空字段声明为 `type: ["string", "null"]`，与真实 JSON 响应一致，不改变其它领域的 Schema 解析。无效／撤销码为 404 `share_not_found`，未授权视图为 404 `share_not_authorized`；公开响应禁止缓存。

隔离回归命令：`./gradlew apiSchemaTest --tests '*OpenApiSchemaHarnessTest' integrationTest --tests '*OperatorShareMongoTest' --console=plain`。使用专属 TestMongo / TestRedis，不接入现有开发或生产数据库；HTTP 测试隔离了内测准入业务，保留真实 JWT、安全链、控制器、序列化及数据库。
