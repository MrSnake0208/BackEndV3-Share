# 招募档案 API（MVP）

个人路径 `/v1/recruitment`，管理员目录路径 `/v1/admin/recruitment-catalog`。成功沿用 `{status_code:200,data:...}`；错误使用真实 HTTP 状态与 `{error:{code,message}}`。除 GET `/catalog` 外个人接口须 JWT、内测资格及账号所有权。个人读取不创建档案，不播种目录；响应 `Cache-Control: no-store`。卡池定义和 UP 名单仅由管理员维护。

## 读接口

- `GET /catalog?game=如鸢|代号鸢` → `{catalog_revision,pools}`。池字段 `pool_id,game,name,pool_type,start_date,end_date,enabled,revision,up_agents`，兼容保留派生的 `up_agent_ids,up_agent_names,unmapped_up_agent_names,up_status`；未知日期为空。目录含停用池和退役槽以便解析历史，新增时服务端校验启用状态。
- `GET /archive?account_id=...` → `{account_id,game_snapshot,archive_revision,baseline,current_pool_id,pools,temporary_agents,summary,pool_summaries,game_mismatch}`。pool_summaries为以pool_id为key的summary对象，不含账号baseline，基于服务端完整历史聚合；空档案 revision=0、baseline=0；pools 自动展示账号真实游戏的全部公共卡池（含停用池），合并已有个人池，保留其 ID 和进度。未保存的目录池使用响应中的稳定 pool_id，progress=null；读取不创建档案，summary 只统计实际保存的状态。游戏快照不一致时仅投影原个人池，保持只读。
- `GET /events?account_id=...&pool_id=...&cursor=...&limit=50&order=desc&date_from=YYYY-MM-DD&date_to=YYYY-MM-DD` → `{items,next_cursor,archive_revision}`。limit 1–100，pool/date可省略；order为asc（从早到晚）或desc（从新到旧，默认），按用户录入/调整的顺序排序，不声称真实获得时刻排序。游标绑定作用域、筛选、方向和revision，修改后继续旧页返回409，应重新读首页，切换方向须从首页重读。日期筛选不包含未知日期。尚未保存的本游戏公共池也可筛选，返回空历史，不写入个人档案。
- `GET /batches?account_id=...&pool_id=...&cursor=...&limit=50` → `{items,next_cursor,archive_revision}`，仅有效计数批次；pool/cursor可省略，limit1–100，按创建时间/ID倒序。包括节点已单独删完但计数仍有效的批次。游标绑定scope/pool/revision，旧页409。与archive/events组合时比较三者revision；同样支持未保存的本游戏公共池筛选。

`pools` 条目：`{pool_id,snapshot,progress,mapped_snapshot}`。snapshot=`{name,game,catalog_pool_id,start_date,end_date,up_agents,up_agent_ids,up_status,up_agent_names,unmapped_up_agent_names,catalog_revision}`；up_status目录枚举为verified/partial/selection/unknown，仅verified且名单非空时可辅助UP判断。`mapped_snapshot` 仅为旧用户临时池的存量兼容字段，不能再新建或映射临时池。progress=null表示未知。目录展示不等于用户已开始抽取；只有实际保存的未知进度计入账号 unknown_progress_count。显式使用兼容 pool_create 时必须提供初始进度：未知填null，确认没有尾抽填0，已知则填非负整数；不得将未确认的进度默认为0。已保存卡池的未知进度计入summary的unknown_progress_count，当前录入前需先校准。

`up_agents` 条目：`{id,name,operator_id,active}`。管理员配置的 N 个有效项仅代表 UP 名单，非 UP 仍可从本游戏绝密图鉴选择。`id` 是固定的池内身份，格式为 `<catalog_pool_id>:up:<标识>`；`operator_id=null` 表示占位，管理员绑定图鉴密探后仍保留同一 `id`。选 UP 项录入时 `agent_id` 使用槽 `id`、`temporary=false`；选图鉴非 UP 时使用官方图鉴 ID。新增或编辑换选时直接提交当前 UP 的官方 ID，服务端也规范为同池槽 ID；用户明确填写的 `up_status` 不被覆盖。普通用户不能创建槽或绑定图鉴。

`temporary_agents` 条目 `{agent_id,name,mapped_agent_id,mapped_name}` 仅保留旧数据。事件原始agent_snapshot=`{agent_id,name,temporary,catalog_revision,rarity:5}`。GET archive/events 根据当前管理员目录投影最新池名、UP 名和图鉴绑定；只改变读响应，不修改个人 revision、事件 ID、抽数、进度、顺序、日期、备注和批次。export 保留原始快照与固定引用，同时更新池快照的 `up_agents` 解释信息，让离线备份保存当前占位绑定；恢复后目录优先，备份不能修改公共定义。pool snapshot 另保留 pool_type；目录无法解析时原快照仍可解释旧记录。

事件：`{event_id,pool_id,pool_snapshot,agent_snapshot,pull_span,sort_order,up_status,acquired_date,note,source,batch_id,created_at,updated_at,deleted_at,deleted_revision,import_batch_id,imported_at}`。up_status为`up|non_up|unknown`，来源首版`manual`；pull_span正整数或null，acquired_date日期或null；响应不暴露数据库_id/user_id/account_id。import_batch_id/imported_at是独立导入元信息，不替换原source。批次也可携带同类导入元信息。

acquired_date和snapshot的start_date/end_date在API与Mongo内均为YYYY-MM-DD日历日期字符串，不用时间点表达；UTC RFC3339时间仅用于created_at/updated_at等时间戳。日期和时间字段仅接受对应格式的字符串或可选null，拒绝数字、数组及非法日期422；跨服务器时区读取不应改变日期或日期筛选结果。

summary=`{known_total_pulls,recorded_pulls,batch_pulls,known_progress,event_count,exact_event_count,unknown_event_count,unknown_progress_count,has_unknown}`。总数=baseline+有效独立事件span+有效批次total+已知进度。批次内span不重复贡献累计；unknown标记不能解释为0抽事实。

## 管理员目录

以下接口须 JWT 与 `recruitment_catalog:write`，复用现有平台管理员/超级管理员权限；普通登录用户返回403。GET 不写入 Hub 数据库；经核对的静态目录是只读初值，管理员覆盖保存在 `recruitment_catalog`。

- `GET /v1/admin/recruitment-catalog` → `{pools}`，含两游戏、停用池及退役槽。
- `POST /v1/admin/recruitment-catalog` → 新池，`expected_revision=0`。
- `PUT /v1/admin/recruitment-catalog/{poolId}` → 更新，路径 ID 须匹配 body，`expected_revision` 必须等于当前 `revision`，并发修改409。

写 body：`{pool_id,game,name,expected_revision,start_date?,end_date?,pool_type?,enabled?,up_agents?}`。UP 数量等于 `up_agents` 中 `active=true` 的数量，最多120个有效项、500个含退役项。槽 ID 必须属于本池；`operator_id` 非空时须为同游戏 rarity=5 图鉴密探，有效槽不得重复绑定同一图鉴密探。`pool_id`、游戏和已有槽 ID 保持稳定。省略旧槽会将其退役保留；`enabled=false` 停用卡池。停用/退役阻止普通新增结果，仍可读取、维护既有记录和恢复真实历史备份。绑定占位时无需改写个人历史。

## 写接口

`POST /commands` 公共外壳：

```json
{"account_id":"acc_...","expected_revision":0,"request_id":"uuid","operation":"progress_set","data":{"pool_id":"catalog:admin_pool_id","progress":0}}
```

成功→`{archive_revision,event_ids,pool_id,agent_id,batch_id}`，调用后GET权威状态。每次新草稿/重确认用新request_id；同作用域成功原请求重试先返回原结果，即使expected_revision过期也成功，且不重复发送SSE。同request_id不同内容409。

set_current_pool、progress_set、event_create、batch_create 可直接使用 archive 响应中的未保存公共池 ID，服务端在同一账号 fence / CAS 事务内按需保存目标池，不需先 pool_create。新保存的池默认进度未知，校正命令的明确值除外；current 录入仍要求先校正，historical 可直接补录。未操作的目录池不会保存，也不会进入 export；启用状态校验继续用于新增结果。兼容保留 pool_create，但页面不再提供手动添加入口。

| operation | data |
| --- | --- |
| pool_create | `{progress,pool_id?,catalog_pool_id}`，progress须显式null或非负整数；目录须为本游戏启用的管理员卡池，提供name或缺少目录ID拒绝422 |
| set_current_pool | `{pool_id}` |
| progress_set | `{pool_id,progress}`，非负整数或null；N由客户端按40-N换算 |
| baseline_set | `{baseline}`，账号级非负整数 |
| event_create | `{pool_id,mode,entries,tail_progress?,extract_from_baseline?}` |
| event_update | `{event_id,entry}`，完整替换展示业务字段；不得改ID/池/批次 |
| event_delete | `{event_id}` |
| event_restore | `{event_id}`，expected_revision须等于删除返回revision |
| event_reorder | `{pool_id,event_ids}`，有效事件完整ID序列，最早到最新；最多20000 |
| batch_create | `{pool_id,mode,total_pull_count,entries,tail_progress?,batch_id?,extract_from_baseline?}` |
| batch_delete | `{batch_id,confirm_total_pull_count}`，确认当前批次数量，整批软删除 |
| batch_restore | `{batch_id}`，仅在删除revision没有后续写入时撤销 |

entry=`{agent_id,pull_span,event_id?,up_status?,acquired_date?,note?}`，每次1–120条。新增密探须是该启用池的有效 UP 槽，或同游戏绝密图鉴密探；其他池的占位不可串用。`temporary_agent_create/pool_map/agent_map` 均拒绝422；旧临时池和密探保持可读、可导出以及已存在记录的维护、删除/撤销，不作为新增入口。事件ID为1–128位字母数字/`._:-`，名称1–128字符，备注≤1000字符。未知字段/小数/类型错拒绝422。

`mode=current` 的独立精确事件要求旧进度已知、首条span大于旧进度、全部span已知、tail_progress显式提供（非负或null未知），提交同事务消费旧进度并设置尾抽；已知累计增量=sum(spans)-old_progress+已知tail。`mode=historical` 不改变进度；extract_from_baseline=true时同事务按本次明确计入量从基准扣除，余额不足拒绝。未知独立span不计入已知总数，不能单独从基准提取。

`batch_create` 保存已知总量、最多120个绝密节点；span可部分未知，但已知span之和不得超出total_pull_count。批次总量不包括tail_progress。current模式要求旧进度已知、total大于旧进度，tail可为null表示未知；historical不改进度。更新批次内某个span不能导致已知span之和超出批次总量；累计仍只计批次total一次。删除批次中的单个节点只移除该结果，批次计数不变；只有整批删除才移除total。整批撤销只恢复此次批量删除的节点，不复活此前已删除节点。

删除独立B31：A17+B31+C17→A17+C17，累计65→34；其他span/progress/baseline不变。撤销同ID，旧撤销409。所有真实写入原子CAS、请求记录和生命周期fence，SSE只在commit后发送`recruitment_changed`。

上限：基准/单次抽数/进度≤1,000,000,000；个人池≤500，旧临时密探兼容上限1000。HTTP401未认证，403内测/权限，404无账号/节点（不泄露他人账号），409revision/重复ID/游戏快照/过期撤销，422校验。存量game_mismatch可读，普通写409。有实质招募数据（含tombstone）不能改游戏；仅公共池+零进度的偏好和零基准不锁游戏，改游戏事务内移除这类空档案/请求。首版无整模块清空；账号删除级联清除所有招募集合。账号更新与删除分别提交后推送account_updated（game）与account_deleted，客户端重新读取账号列表。

账号生命周期保护包括原有密探分享接口：分享码创建/更换/撤销只执行所属账号的非upsert字段CAS，不能用迟到的整账号快照覆盖game/name/招募fence或复活已删账号。若撤销时分享码已经更换，返回409 `share_code_changed`，刷新后重试。

## JSON备份与恢复

`GET /export?account_id=...` → `{schema:"yuanhub.recruitment.v1",exported_at,source_account:{account_id},game,archive_revision,baseline,current_pool_id,pools,temporary_agents,events,batches}`。data可直接保存为JSON文件。读取同一Hub事务快照中的完整档案，包含所有有效/已删除事件和批次，不使用分页，不输出截断备份；存量游戏不一致允许导出。所有时间UTC RFC3339，纯日期YYYY-MM-DD，未知保持null。source原值、pool_type、rarity、个人原始/映射快照保留；池快照的up_agents补入当前管理员解释信息（含退役槽和operator_id），不改写数据库或事件快照。目录缺失时使用原有槽快照。数据库ID、user_id及认证信息不进入文件。

机器协议：[recruitment-exchange-v1.schema.json](schema/recruitment-exchange-v1.schema.json)。紧凑UTF-8 JSON文档最大5MiB；事件和批次各最多20000（包含删除标记），导出、预览、合并结果超限整包422。服务端额外验证唯一稳定ID/顺序、同游戏、所有引用、删除时间/版本配对和批次有效span总和≤total。不接受未知字段、小数抽数或错误类型；池进度必须显式提供（未知为null），历史密探快照rarity必须显式5。真实管理员池即使停用、UP槽退役仍能恢复历史；新增池引用必须存在于管理员目录，新增结果须引用该池固定UP槽或本游戏绝密图鉴。伪造catalog_pool_id、跨池占位、新增用户自定义池/临时密探均拒绝422，备份不能写公共目录。已存在的旧临时项可导出、判重及冲突保留，不能借导入新增旧临时池的结果或批次。

`POST /import/preview`：

```json
{"account_id":"acc_target","document":{},"options":{"state_strategy":"keep_current","confirm_count_change":false}}
```

options可省略，默认为上述值。`state_strategy`只有`keep_current`/`use_backup`。返回：

```json
{"preview_token":"recruitment_preview_uuid","document_hash":"sha256","target_revision":7,"expires_at":"2026-10-01T01:10:00Z","items":[{"entity_type":"event","id":"e1","status":"count_overlap","reason":"尚未明确确认抽数覆盖风险"}],"stats":{"added":0,"duplicates":0,"conflicts":0,"skipped":1},"risks":["计数覆盖风险说明"],"current_known_total":100,"backup_known_total":80,"candidate_known_total":100,"can_commit":true}
```

逐项entity_type为pool/agent/event/batch/state；status为add/duplicate/conflict/skip/count_overlap。stats统计每类计划条目（包含state）；conflict保留当前，其关联新增项跳过。预览无任何业务写入/SSE。token绑定用户、目标账号、原文档hash、选项hash和目标revision，有效10分钟；进程内最多4096个，过期/被淘汰/服务重启后必须重新预览。多实例部署须共享预览存储或sticky routing，当前预览不跨实例；已经成功的请求仍可跨重启按持久化记录重试。修改选项或文件须新预览，hash无关对象字段排列但保留数组排列。

空逻辑档案（无事件/批次/临时密探、零基准，且仅有已明确进度为零的公共池偏好；临时池和未知进度属于实质数据）恢复备份全部逻辑状态。源archive_revision只是备份元数据，目标从当前CAS revision递增；导入tombstone采用目标不可撤销标记0，旧源删除版本不会变成当前撤销权限。

已有档案按稳定ID和业务内容判重，忽略导入元信息、存储/更新时间、目标排序号与目标删除revision；管理员池同catalog_pool_id/game的名称、日期、名单、映射等显示元信息，以及非临时密探同agent_id/rarity的名称、目录版本，不作为业务冲突。固定池/密探身份、span、UP确认状态、获得日期、备注、source、批次和删除状态仍参与判重；旧用户临时池/密探的名称仍参与。管理员绑定占位后再次导入旧备份不会伪报冲突。同ID业务内容或删除状态不同为conflict。当前tombstone不复活，备份tombstone不删除当前live；新tombstone以不参与抽数的记录导入。新增事件在保留目标顺序后按备份相对顺序追加，重复项不改变当前用户顺序。原source保留，每次新导入单独记录import_batch_id/imported_at。

默认保留当前baseline、progress和偏好；新池进度为未知。新增独立事件/计数批次遇到当前或备份的非零基准、同池未知/非零进度、已有有效批次时默认count_overlap跳过；关联批次未导入则其节点不能拆开导入。已有同ID同内容批次的新节点仅在不超过该批次总抽数时增加，批次总量不重复计入。确认覆盖风险后设置confirm_count_change=true重新预览，再核对candidate_known_total。`use_backup`替换基准和匹配卡池进度、采用备份当前池，保留当前独有记录/池；状态从不相加，有状态差异时必须明确确认候选总抽数，否则can_commit=false。冲突池的状态仍保留当前。此功能不自动判断两个文件是否覆盖同一段现实抽卡历史。

`POST /import/commit`：原预览body增加`preview_token,document_hash,expected_revision（target_revision）,request_id`，options必须相同。成功返回commands同形的`{archive_revision,event_ids,pool_id:null,agent_id:null,batch_id:null}`；客户端重读权威状态。账号所有权、游戏、token绑定、期限、文件hash、revision、选项和候选可提交状态都复核，生命周期fence、档案CAS、所有新增批次/事件、成功请求记录在一个Hub事务内原子提交；失败全部回滚。SSE仅提交后发送一次。使用原request_id和完全相同body成功重试先读持久化结果，即使token过期、服务重启或revision过时仍返回原结果；同request_id不同内容409。新request_id再次导入同文件不新增重复记录，但会保存新的成功请求并推进revision。

失效token409 `recruitment_preview_expired`；身份/文件/选择不匹配409 `recruitment_preview_mismatch`；并发目标变化409 `recruitment_revision_conflict`。完整导入失败保留当前档案。首版不提供清空、覆盖删除当前记录或跨游戏恢复。

CSV由前端使用同一完整export文档生成，标准CSV转义并处理所有用户文本的公式注入，row_type区分event/baseline/pool_state；密探名称按同一个人卡池的固定UP身份采用当前绑定解释，无法匹配时回退原事件快照。CSV供阅读和迁移辅助，不保证往返恢复。
