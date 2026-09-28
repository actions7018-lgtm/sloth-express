# 树懒快递助手 ParcelHub

树懒快递助手（Android，MVP V0.1）。项目名：快递聚合助手 / ParcelHub。

**本仓库支持监听第三方 App 的系统通知（淘宝 / 京东 / 拼多多 / 菜鸟 / 丰巢 / 顺丰 / 中通 …），
并自动保存其中的取件码、取件地点、运单号与物流状态**，把同一包裹的多条通知合并成一条记录，
到站与出码时用 App 自己的通知提醒你。**License: MPL-2.0 · Maintainer: actionsk**。

- 事件驱动，**无轮询、无 WakeLock、无常驻网络**；快递解析与保存都在本机，物流查询按需联网。
- 不读短信、不申请定位 / 相机等无关权限；通知正文只在内存中解析，落库前统一脱敏。
- 规则单一数据源：`app/src/main/assets/rules.json`（本地预编译，不联网热更新）。

> **改动必须落文档**：每完成一次改动都要同步更新 [`CHANGELOG.md`](CHANGELOG.md)
> （按日期倒序追加：新增 / 修复 / 变更 / 测试）；涉及架构、状态口径、构建环境的改动
> 还要同步本文件对应章节。

---

## 1. 构建与测试

```powershell
$env:JAVA_HOME = "D:\AndroidStudio\files\jbr"
Set-Location D:\codex\ParcelHub

# 单测（SOP 要求：每完成一个模块先通过编译和对应测试）
.\gradlew.bat :app:testDebugUnitTest --console=plain

# 打包
.\gradlew.bat :app:assembleDebug --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

安装到真机：

```powershell
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
# 授予通知使用权（系统设置里手动开，或 adb 写回）
adb -s <serial> shell cmd notification allow_listener com.parcelhub/com.parcelhub.ingest.NotificationListener
```

### 1.1 构建内存配置（本机踩坑）

`gradle.properties` 里的 JVM 上限是**下调过的**：

```properties
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m
```

原因：本机总内存 15.4GB，闲时可用常常只剩 3GB 左右。原来 Gradle daemon 与 Kotlin daemon 各占
`-Xmx3072m`，编译到 `compileDebugKotlin` 阶段会被系统直接杀掉进程，表现为
**没有任何编译错误、也没有 EXIT 行、耗时固定约 2 分钟**——极易误判成代码问题。
内存宽裕的机器可以把这两个值调回去。

### 1.2 真机验证数据库（本机没有 sqlite3 时）

```powershell
adb shell "run-as com.parcelhub cp /data/data/com.parcelhub/databases/parcelhub.db /sdcard/d.db"
adb shell "run-as com.parcelhub cp /data/data/com.parcelhub/databases/parcelhub.db-wal /sdcard/d.db-wal"
adb pull /sdcard/d.db .\db\parcelhub.db
adb pull /sdcard/d.db-wal .\db\parcelhub.db-wal   # WAL 必须一起拉，否则读不到最新写入
# 之后用本地 sqlite（或 python sqlite3）打开即可
```

---

## 2. 模块地图

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 采集 | `ingest/NotificationListener.kt` | 回调只做轻量门禁 + 转发，不做重型工作（SOP §7.1） |
| 采集兜底 | `ingest/ShareEntryActivity.kt` | 分享文本到本 App 导入（SOP §16） |
| 队列 | `ingest/IngestPipeline.kt` | 单队列串行处理，异常隔离不崩溃 |
| 解析 | `parser/ParserEngine.kt` + `*Extractor.kt` | 双层架构：来源解析 → 通用字段提取，正则全部预编译（`CompiledRules`） |
| 合并 | `matcher/ShipmentMatcher.kt` | 运单号 > 指纹 > 同驿站上下文，三级匹配，**不跨单号归并** |
| 状态机 | `matcher/StateMachine.kt` | 平台状态只前进不倒退；平台状态与用户状态分离（SOP §11 / §12） |
| 存储 | `data/`（Room） | `Shipment` / `ParcelEvent` / 来源配置 / 规则版本 |
| UI | `ui/`（Compose） | 首页分组卡片、列表筛选、详情、设置、来源管理、采集诊断、权限引导 |
| 桌面小组件 | `widget/`（RemoteViews） | 「快递待办」Widget：待取件 + 待查询，可在桌面直接勾选完成 |
| 快递灵动岛 | `island/` | 事件 → 去重 → 状态机 → 通知/悬浮双模式；只读 `ShipmentRepository`，**不建第二套库、不加轮询服务** |
| 短信读取 | `sms/`（`SmsReceiver` / `SmsIngestor` / `SmsPermissionManager`） | 来源管理里的「短信」来源（默认开）：`RECEIVE_SMS` → 排除词 + 物流词过滤 → `smsHash` 去重 → **复用 `IngestPipeline`**；只申请 `RECEIVE_SMS`（见 §3.8） |

页面：`首页` / `快递`（列表 + 筛选：全部·待取·运输中·已签收）/ `设置`，首页直达 `采集诊断` 与 `来源管理`。

---

## 3. SOP 验收记录

> 依据：`D:\codex\快递聚合助手_SOP开发需求_v1.1.md` §25 开发顺序 SOP、§29 开发完成验收清单。

### 3.1 Phase 1–6 逐阶段验收

| Phase | 验收线 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 基础工程 | 可编译 / 可安装 / 首页可打开 / 数据库正常 | ✅ | `assembleDebug` BUILD SUCCESSFUL；真机安装后首页 `今日概况` 可见；诊断页「数据库 正常」 |
| 2 NotificationListener | 发测试通知能稳定收到，回调不阻塞主线程 | ✅ | 冒烟 K1：注入 4 条通知 → 诊断页「累计收到通知 4 / 成功解析 4 / 监听服务 运行中」；`onNotificationPosted` 仅门禁 + `enqueue()` |
| 3 解析引擎 | 样本 ≥100 条，成功率 ≥90%，误识别尽量接近 0 | ✅ | `ParserCorpusTest`：**120 条**语料（102 条应识别 + 18 条必须被过滤），**失败 0 条 → 成功率 100%**（验收线 90%）；门禁样本全部被正确拒绝 |
| 4 Shipment 合并 | 同一包裹 5 条通知 → 1 个 Shipment + 5 个 Event | ✅ | `ShipmentMatcherTest` / `DedupEngineTest`；真机 4 条通知（含重复推送）→ **包裹数 3 / 事件数 4** |
| 5 首页 | 事件变化后 UI 自动更新，不需手动刷新 | ✅ | Room `Flow` 驱动；导入样本后无需刷新即出现分组计数（冒烟 A 组断言） |
| 6 App 提醒 | 同一事件不重复弹多条通知 | ✅ | 冒烟 K1：到站事件触发 1 条 `channel=arrival` 通知；`Notifier` 按事件指纹去重 |

### 3.2 §29 开发完成验收清单

#### 功能

| 项 | 状态 | 说明 / 证据 |
| --- | --- | --- |
| 无需输入单号即可从通知发现快递 | ✅ | 监听回调 → 解析 → 建单，全链路真机验证 |
| 可以识别主要来源平台 | ✅ | `rules.json` 收录 12+ 来源；来源管理页可见 |
| 识别运输中 / 派送中 / 到站 / 取件码 / 地点 | ✅ | 语料覆盖 9 种状态；真机 `已到站` / `运输中` / 取件码 / 取件地址均展示 |
| 同一包裹不会重复生成多个 Shipment | ✅ | 4 条通知 → 3 个包裹；跨单号不归并有专门用例 |
| 状态不会倒退 | ✅ | `StateMachineTest` + 真机 E10：补推「运输中」后列表仍为「已到站」 |
| 取件码可以复制 | ✅ | 首页「复制取件码」点击无异常（真机点击 + logcat 无 FATAL） |
| 用户可以标记已取 | ✅ | 详情页「标记已取件」→ 用户状态「已取件」 |
| 标记已取后自动归入「已签收」 | ✅ | `ShipmentFilterTest.picked_up_is_grouped_into_delivered` + 真机 G1/G2/G4（列表 chip、已签收分组、待取移出） |
| 到站后可以发送提醒 | ✅ | 冒烟 K1 到站提醒通知已发出 |

#### 性能

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 无通知时无持续轮询 | ✅ | 代码无 Timer / 轮询循环，全部由通知事件与 Room Flow 驱动 |
| 无 WakeLock | ✅ | 全工程 `grep WakeLock\|WAKE_LOCK` 无匹配 |
| 无常驻网络连接 | ✅ | 清单无 `INTERNET` / 网络权限 |
| Listener 回调不执行重型工作 | ✅ | 回调内只做关键词门禁与字段裁剪，随后 `enqueue()` 入单队列 |
| 正则全部预编译 | ✅ | `CompiledRules` 启动时一次性编译；坏规则跳过并记录，不拖垮解析 |
| 事件单队列处理 | ✅ | `IngestPipeline` 单消费者串行 |
| UI 增量刷新 | ✅ | Room `Flow` + Compose `collectAsState`，只刷新变化的分组 |
| 压测 1000 条无明显内存泄漏 | ⬜ 未做 | V0.1 未执行压测（SOP §25 Phase 8） |
| 长时间后台测试无异常 CPU | ⬜ 未做 | 同上 |

#### 可靠性

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 服务断开后能检测 | ✅ | 诊断页「监听服务 运行中 / 未运行」实时状态 |
| 权限关闭能提示 | ✅ | 设置页三项状态 + 权限引导页（已开启 / 已允许） |
| 解析失败不崩溃 | ✅ | `try/catch` + 坏规则跳过；语料全量通过 |
| 第三方 App 通知格式改变不会崩溃 | ✅ | 规则驱动 + 空值安全；`ParserEngineTest.engine_rejects_empty_body` |
| 重启手机后恢复 / 后台受限提示 / 数据库损坏提示 | ⬜ 未做 | 属 SOP §25 Phase 7 异常场景，V0.1 未覆盖 |

#### 隐私

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 不读取短信 | ✅ | 清单仅 2 个权限（`POST_NOTIFICATIONS`、`BIND_NOTIFICATION_LISTENER_SERVICE`） |
| 不保存无关通知 | ✅ | 双层门禁；语料含 18 条无关通知，全部被拒（`parses=false` 断言） |
| Release 不输出完整敏感通知 | ✅ | `AppLog.d` 仅 `BuildConfig.DEBUG` 输出；日志只含来源包名与原因，从不打印正文；落库摘要经 `PrivacyUtil.summarize` 脱敏 |
| 无必要权限不申请 | ✅ | 无短信 / 定位 / 相机 / 存储权限 |
| 网络按需、范围最小 | ✅ | V0.1 无 `INTERNET` 权限，`rules.json` 为本地 assets；隐私文案不承诺零网络，查询物流时才联网 |
| 诊断导出前明确提示用户 | ✅ | 诊断页文案：「导出诊断样本需经你主动确认后才会进行」 |

### 3.3 真机冒烟（1080×2340 实机）

- 覆盖脚本：`tools\device-smoke.ps1`（开发期使用，断言基于无障碍节点文本与动态坐标，不硬编码分辨率）。
- 当前规模：**13 个阶段 / 78 条断言**。`pm clear` 全新首启跑完整链路：
  首启引导 → 首页空态 → 4 条分享导入 → 列表筛选 / 状态不倒退 → 标记已取件归入已签收 →
  设置文案与开关一致性 → 权限引导页复跑（「重新运行权限引导」→「开始使用」→ 返回首页）→
  来源管理 → 采集诊断（包裹数 / 事件数 / 规则版本 / 通知使用权 / 监听服务）→
  回首页核对「取件地址 → 放置位置」顺序、**取件码标签与数字垂直居中**、**三个操作按钮等宽** →
  **自动查询链路（SOP T03/T06）**：查询任务创建、`auto_query` 入口通知、无障碍服务状态。
- 隐私口径断言：引导页与设置页均**不含「零网络」**，且都只展示「仅处理与快递相关的信息」（隐私说明只留范围口径，不再逐条列举）。
- 放置位置断言分两处（与 App 口径一致）：首页**只**断言站点样本那 1 行 + 「家门口不进首页待取」，
  家门口样本到**详情页**断言「放置位置 = 家门口」。
- 报告：`$env:TEMP\parcelhub-smoke\report.md`（失败时附对应页面截图）。
- 造数脚本：`tools\push-todo-samples.ps1`，一键灌 **20 条模拟快递**并自动校验（`-Fresh` 先清库再灌）：

  | 分组 / 场景 | 条数 | 说明 |
  | --- | --- | --- |
  | 首页「待取」· 驿站站点 | 8 | 有取件码，进首页待取、进 Widget 待办 |
  | 首页「待取」· 代收点 | 8 | 同上 |
  | 投放场景 · 家门口 | 2 | **口径外**：`needsPickup` 排除 → 不进首页待取、不进 Widget，只在列表与详情可见 |
  | 首页「派送中」 | 1 | 无取件码 |
  | 首页「运输中」 | 1 | 无取件码 |

  校验项：首页 `待取（16）/ 派送中（1）/ 运输中（1）` → 列表出现 `取件码 9901/9902` →
  详情 `放置位置 = 家门口` → 桌面 Widget `16 项待办`（6 条/页 × 3 页）。
  计数是绝对值，必须带 `-Fresh` 跑（叠加在旧数据上三组计数与 Widget 标题都对不上）。

脚本里写死的几处真机坑（都是踩过才加的，改脚本前请先读注释）：

| 坑 | 处理 |
| --- | --- |
| 无障碍开关写进 `secure` 后会被荣耀/华为 ROM 回收成 `null` | 必须在 **App 已在前台** 时写，且组件名用系统写回的缩写形式 `com.parcelhub/.autoquery…`；因此开启动作放在第 13 步而不是第 0 步 |
| 第 0 步 `am start` 会打乱「首次启动」与后续页面状态 | 第 0 步只做安装 / 清数据 / 授权，不拉起 App |
| 列表页切筛选后读到上一屏（偶发假失败） | 每次切筛选后 `Wait 2`，等 Room Flow 刷新完再断言 |
| 返回栈深度不固定（第 9 步来源管理会把栈加深） | 第 11 步改成最多 4 次 BACK 的有界循环 + 兜底点底部导航 / 重新拉起 |
| `[regex]::Matches` 对一个 `bounds` 返回 **1 个 Match（4 个捕获组）**，按 `Count -eq 4` 判断会恒假 → 点击函数一次都点不到（`push-todo-samples.ps1` 踩过，表现为「明明在首页却说没找到底部导航」） | 计数判断一律 `Count -eq 1`，取值用 `$m[0].Groups[1..4]` |
| 不带 `-S` 的 `am start` 只是把上次任务带回前台（上次停在列表页 → 首页断言全空） | 回首页断言前用 `am start -S` 冷启动；仍不在首页则精确点底部「首页」兜底 |
| 底部导航用 `contains` 匹配会误点到含「快递」的卡片文案 | 导航点击用**精确匹配** `Tap-Exact`；首页「派送中/运输中」在 16 张卡下面，必须先滑到底/从顶重扫（`Find-Text-Scrolled` + `Scroll-To-Top`） |
| `pm clear` 会重置「看过引导」标记，`-Fresh` 造数后首屏是引导页 | 先判「先跳过，稍后再开」并点掉，再做首页断言 |

### 3.4 用户追加的三条 UI 需求

| 需求 | 实现 | 验证 |
| --- | --- | --- |
| 点「已取件」后，包裹自动归纳到「已签收」 | `ShipmentFilter`：`isArchived = isFinished \|\| userStatus==PICKED_UP`，已取件同时移出「待取」 | 单测 `picked_up_is_grouped_into_delivered` / `picked_up_leaves_pickup_group` + 真机 G 组 |
| 取件地址下方显示取件位置 | 首页卡片新增「放置位置」行，展示完整投放点（如 `余杭区东连街道菜鸟驿站`、`家门口`）；详情页同步展示 | 真机导入两类样本逐一核对；取件地址仍显示短站点，两者不重复 |
| 取件码与后面的数字垂直居中 | 首页卡片取件码行 `verticalAlignment = Alignment.CenterVertically`（标签小字号 + 数字大字号，不底对齐） | 冒烟断言「首页取件码标签与数字垂直居中」：label=834 / code=834，delta=0 |

「放置位置」的取值严格来自通知原文（`rules.json → locations.placePatterns`），**不猜测地址**（SOP §6.4）：

- 完整站点地址：`余杭区东连街道菜鸟驿站已收到您的包裹…` → 取件地址 `菜鸟驿站`，放置位置 `余杭区东连街道菜鸟驿站`
- 门口投放：`您的快递已放在家门口…` → 放置位置 `家门口`
- 通知里没有更细信息时**不显示该行**，只保留取件地址。

---

### 3.5 数据库与状态口径（改状态相关代码前必读）

Room 当前 `version = 3`（`data/db/AppDatabase.kt`），迁移**都保留既有数据**：

| 迁移 | 内容 |
| --- | --- |
| `MIGRATION_1_2` | 新增 `query_tasks` 表（自动查询任务，SOP §9 / §29） |
| `MIGRATION_2_3` | `shipments` 增加 `picked_up_at`（用户点「已取件」的时间） |

`shipments` 里容易混淆的几列：

| 列 | 含义 | 谁来写 |
| --- | --- | --- |
| `status` | **平台状态**（运输中 / 派送中 / 已到站 / 已签收…），只进不退 | 状态机，按通知推进 |
| `user_status` | **用户状态**（未处理 / 已读 / 已取件 / 已忽略） | 只有用户操作才改（SOP §4.3 平台/用户状态分离） |
| `picked_up_at` | 用户点「已取件」的时间戳，未取件为 NULL | `setUserStatus(PICKED_UP)` |
| `pickup_location` | 取件地址（短站点，如 `菜鸟驿站`） | 解析层 |
| `address` | 放置位置（完整落点，如 `余杭区东连街道菜鸟驿站`、`家门口`） | 解析层 |

归组口径集中在 `data/entity/ShipmentEntity.kt`，**UI / 列表 / Widget 共用同一套**：

| 判定 | 规则 |
| --- | --- |
| `needsPickup`（待取 / 待办） | 已到站或入柜或有取件码，且非平台终态、**不是家门口投递**、用户未取件未忽略 |
| `isDeliveredToDoor` | 用 `PlacementClassifier` 判放置位置为「家门口」；「驿站门口」不会被误判（先判站再判门） |
| `isArchived`（已签收） | 平台终态，或用户已取件 |
| `isActiveOutForDelivery` | 首页「派送中」：平台派送中且用户未取件 |
| `isActiveInTransit` | 首页「运输中」：非待取、非派送、非终态且用户未取件 |

状态角标（`StatusChip`）单独一套口径：**用户状态优先于平台状态**——`userStatus` 为已取件 / 已忽略时
一律显示「已取件」/「已忽略」，否则按平台状态显示（待取 / 派送中 / 已签收…）。判定与文案在纯函数
`chipStyleOf` / `chipLabelOf`（`ui/components/Components.kt`），**三处调用点（详情页 / 列表页 / 首页）
都必须把 `userStatus` 传进去**，漏传就会出现「已取件的包裹还挂着待取角标」。

写操作约束：`ShipmentRepository.setUserStatus()` 走 DAO 的
`UPDATE shipments … WHERE id = :id`（**按主键更新并返回受影响行数**），返回 `Boolean`；
只有 `rows > 0` 才算成功，UI 才能更新；失败必须提示「标记失败，请重试」，不允许只改内存对象。

### 3.6 桌面小组件「快递待办」

| 项 | 实现 |
| --- | --- |
| 架构 | Android 官方 App Widget（`RemoteViews` **固定布局** + `addView` 动态填充条目） |
| 入口 | `widget/TodoWidgetProvider.kt`、`widget/TodoWidgetData.kt`、`widget/TodoWidgetItem.kt` |
| 数据源 | **只读 App 自己的 Room 数据库**，不另建表、不缓存快照 → 数据库 / App 页面 / Widget 天然一致 |
| 待办口径 | `needsPickup`：已到站/入柜/有取件码，且未取件、未忽略、**不是家门口投递**（三处共用同一口径） |
| 排序 | 有取件码的「取件任务」在前，无取件码的「待查询」在后；组内按更新时间倒序 |
| 条目 | `☐ 📦 取件码 99-9-7515｜菜鸟驿站`；待查询为 `☐ 待查询｜菜鸟驿站` + 单号小字（此时才显示单号） |
| 点击 | **左侧方框 → 标记已取件**；**条目主体 → App 对应包裹详情**（两条点击互不干扰）。方框命中区域 26×24dp（图标视觉 18dp，靠 padding 撑开）；**标题「N 项待办」→ 打开 App** |
| 完成反馈 | 桌面点方框 → **先写库成功**才显示勾选态，再走 ~30fps 逐帧动画（约 0.6s：✓ pop → 整行淡出 + 下面条目同步上滑）后移除、顶部计数 −1；写库失败弹「标记失败，请重试」。这段用 `goAsync()` 兜住广播生命周期，否则进程可能在等待期被回收、条目卡在勾选态。**勾选项按「写库前记下的位置」插回原位**（`anchors`），不重排整页，动画在用户点勾的那一行**就地**播放 |
| 刷新策略 | `updatePeriodMillis=0` **零轮询**；仅①数据变化（Room Flow 观察者）②桌面点勾选③首次添加到桌面 时刷新；桌面无 Widget 时直接跳过 |
| 空状态 | 无待办时显示「暂无待取快递」+ 提示，不显示空白 Widget |
| 与自动化解耦 | Widget 不碰菜鸟、不触发无障碍，只改「用户状态」（需求：Widget 与快递状态引擎解耦） |
| 兼容性 | 不依赖菜鸟是否安装；深浅色各一套配色（`values/` + `values-night/`） |
| **容量与翻页** | 一屏放几条 = `(桌面给的高度 − 29) / 25.7`（`TodoWidgetCapacity.rowsFor`，固定开销 29dp = 上内边距 5 + 下 4 + 标题行 19（真机 dump 57px/3x）+ 间距 1；行高 25.7dp = 24dp 方框 + 上下内边距合计 5px），至少 1 条、封顶 12 条；本机 4x2 格实测 **6 条/页**（29 + 6×25.7 = 183.2 ≤ 184，硬约束有单测钉死）。**超出一屏不滚动，改为翻页**：标题行**右上角**显示「N 项没显示 · 点此翻页 ›」，末页变「最后一页 · 点此回第一页 ›」，页码存私有 prefs（`widget_todo` / `page`），翻页字放标题行、**不占条目区高度** |
| **行间距 +5px** | 用户要「取件码上下行间距增大 5px」且**保住一页 6 条**（当时余量只剩 15px）→ 行高从 72px 加到 77px（`values/dimens.xml`：`0.83dp + 1dp`，3x 下取整成 2px+3px=5px；拆成两个值是为了凑出 dp 表示不了的奇数 5px），代价从组件自身上下留白里扣（8/6dp → 5/4dp，间距 2dp → 1dp）。改这两个值必须同步 `TodoWidgetCapacity` 与 `TodoWidgetCapacityTest` |
| **勾选动画的白名单** | `RemoteViews` 的反射入口（`setInt`/`setFloat`）还要求目标方法带 `@RemotableViewMethod`，否则桌面 `apply()` 抛 `ActionException` → 弹「加载窗口小工具时出现问题」、整块组件变空（真机踩过：`setScaleX` 就是这样把组件打坏的）。动画因此只用白名单放行的三条通道：`TextView.setTextColor`（文字淡出）、`ImageView.setImageAlpha`（方框/图标淡出）、**不走反射**的 `RemoteViews.setViewPadding`（✓ pop 改方框内边距、预滑给本行设负上内边距）。`View.setAlpha / setScaleX / setTranslationY` 全部被拒 |

**为什么不用可滚动的集合 Widget（`RemoteViewsService` + ListView）？**

最初按「可滚动列表」实现并跑通了渲染，但真机暴露出硬限制：**集合 Widget 的 item 里，
子视图的点击在这台设备（荣耀 / 华为桌面）上完全不生效**，只有 item 根视图的点击会被处理。

实测现象：把「点方框 = 完成」挂在 checkbox 上，点下去毫无反应（连 `fill-in intent` 子视图方案
也无效）；而挂在 item 根视图上的「点条目 = 详情」正常。也就是说
**「点方框完成」与「点条目看详情」两个动作在集合 Widget 里无法共存**。

因此改为固定布局 + `RemoteViews.addView`（非集合布局里子视图点击是标准能力），
现在两个动作都能用：`dumpsys` 验证 + 真机点击均通过。代价是列表不可滚动、超出一屏要靠翻页。

**那能不能在固定布局里塞一个 `ScrollView` 真滚动？也不能 —— 白名单不允许。**

把 `widget_items` 包进 `ScrollView` 后 `apply()` 直接抛 `Class not allowed to be inflated`，
华为桌面弹「加载窗口小工具时出现问题」、整个 Widget 变成空白（真机踩过，已回退为普通
`LinearLayout` + 翻页）。`RemoteViews` 只允许固定的有限控件集，`ScrollView` 不在其中；
集合类控件（ListView/StackView）虽在白名单里，但会撞上上面「item 子视图点击失效」的问题。

**高度取值踩坑**：容量按 `getAppWidgetOptions` 的高度算，必须取
`OPTION_APPWIDGET_MAX_HEIGHT`（本机 184dp）；取 `OPTION_APPWIDGET_MIN_HEIGHT`（132dp，
是「可缩到的最小值」）会算出每页 1 条、下面空一大片 —— 真机实测踩过。
`onAppWidgetOptionsChanged` 会回调重算，桌面拖动改变尺寸也能自适应。

**翻页的循环必须自己算**：只把页码 +1 交给渲染层夹取的话，末页 +1 会被夹回末页，
永远回不到第一页（真机踩过）。所以 `flipPage` 先 `clampPage` 出当前页，再 `(current + 1) % pages`
取模，末页点一下回到第一页。

---

### 3.7 快递灵动岛

按《快递灵动岛功能开发 SOP》实现，**完全复用现有数据层**（SOP §24：不新建第二套数据库、
不为灵动岛建轮询服务）。

**一条链三端共用一份状态**（SOP §3）：

```text
ShipmentRepository（唯一数据源）
   ├─ 入库结果        → IngestPipeline.process → IslandManager.onIngest
   ├─ 标记已取件成功   → ShipmentRepository.onUserStatusChanged（三个写入口共用的 choke point）
   └─ 数据变化        → observeAll() → 桌面 Widget 自动刷新
                ↓
   IslandEventDispatcher（映射 + eventId）→ IslandDedup（防重复）
                ↓
   IslandStateMachine（HIDDEN→COMPACT→EXPANDED→SUCCESS→COMPACT→HIDDEN）
                ↓
   IslandNotificationManager（默认） / IslandOverlayService + DynamicIslandView（悬浮）
                ↓
   首页 / Widget / 灵动岛 状态一致
```

**事件**（SOP §6）：`Arrived` / `Delivering` / `PickedUp` / `StatusChanged`，只带 `shipmentId`。
映射保守：到站需「到站态 + 有取件码 + 首次发现」；`Delivering` 只认进入 `OUT_FOR_DELIVERY`；
`StatusChanged` 只对 `DELIVERED / RETURNED / CANCELLED`；运输中/发货不产事件（维持 `transitEnabled` 现状）。
**重复、低置信、`shipmentId<=0` → null**。

**接入点只有两处**，避免双弹（同一件事弹两条）：
- `IngestPipeline.process`：灵动岛接管则**不再**调用普通提醒 `notifier.onOutcome`；
- `ShipmentRepository.onUserStatusChanged`：**写库成功（影响行数 > 0）才回调**，失败绝不给成功反馈（SOP §9）。

**防重复**（SOP §15）：`shipmentId_事件_日期桶`，例 `7515_ARRIVED_20260927`。
`IslandSeenStore` 接口 + 内存实现（单测）/ `SharedPreferencesSeenStore`（跨重启），`rollDay` 跨天清空。
日期桶用 `SimpleDateFormat("yyyyMMdd")`——minSdk 24 无脱糖，不能用 `LocalDate`。

**双模式**（SOP §11）：
| 模式 | 触发 | 行为 |
| --- | --- | --- |
| 通知（默认） | 总开关开即可，**零额外权限** | channel `island`，固定 id `0x151A17`（新事件覆盖旧的）、`setAutoCancel` 点击进详情、SUCCESS 态 2s 后自动 cancel |
| 悬浮 | 用户主动在设置页开「悬浮显示」并授 `SYSTEM_ALERT_WINDOW` | 胶囊盖在其他应用上层；缺权限时跳系统授权页，返回自动补开（`LifecycleResumeEffect`），任何失败**回退通知模式**（SOP §13 / §19） |

悬浮版由 `IslandOverlayService` 托管：每次事件用 `startForegroundService` 拉起，O+ 收到 SHOW 立刻
`startForeground`（channel `island_overlay`，IMPORTANCE_LOW）；胶囊消失即 `stopForeground(true)+stopSelf`。
**不常驻、不轮询**。视图用原生 View（悬浮窗口没有 LifecycleOwner，`ComposeView` 会崩），
背景用 `GradientDrawable` 程序化生成，不加 res 资源。

**胶囊尺寸按 OPPO 流体云（ColorOS 灵动岛）口径**（2026-09-28 调整）：
- 底色 **纯黑 `#000000`**、标题纯白 14sp 加粗、副行 70% 白（OPPO 规范原文「胶囊背景固定为黑色，
  不允许开发者自定义」，2026-09-28 按用户要求改成 OPPO 样）。`快递灵动岛功能开发SOP.md` §17
  已同步改写为 OPPO 口径（原「浅色 / 柔和渐变」）；§17 真正反对的「纯黑**巨大**胶囊 / 大面积遮挡
  顶部」不在此列（本体只有 40dp × ≤33% 屏宽）。
- **固定盒 高 40dp × 宽 屏宽 33%**：`onMeasure` 强制 `setMeasuredDimension(屏宽33%, 40dp)`，
  两行子 View 行盒固定（标题 17dp + 间距 1dp + 副行 16dp，按 `fontScale` 等比放大），
  emoji / 省略号 fallback 字体的行高差不再透上来 → **到站 / 取件完成 / 小胶囊三态等大**
  （用户要求「取件完成要和到站一样大」）；圆角 = 实测高 ÷ 2（layout 监听 + 渲染后各校正一次）；
- 内边距 水平 14dp / 垂直 3dp，**最多两行** —— 标题 14sp 加粗 + 副行 11sp，两行都单行、超长省略；
- 宽度上限 = **屏宽 33%**（`maxWidth` 落到两个 `TextView` 上），本机 1080×2340 / 480dpi 实测 **118.7dp 宽**；
  副行在最小字号 8sp 下仍差几个像素时，按**实差**放宽盒宽（只增不减，硬顶屏宽 **40%**）；
- 贴**状态栏下方 4dp**、水平居中（实测中心 x=539，屏心 540）。
- 副行**只放 `line1`**（取件码 / 正在派送 / 单号）：`line2`（地址、承运商）留给通知模式与详情页。
- **副行（取件码）永不省略**（2026-09-28 用户口径）：`ellipsize = null`，超长先从 11sp 按 0.5sp 收缩到
  下限 8sp，仍差几个像素就放宽盒宽；字色为 70% 白（一度改过 SUCCESS 绿 `#4CD964`，按用户要求改回白色）。
  标题（≤5 字）超长仍走省略号兜底。
- 标题文案压到 **≤5 字**（`📦 快递到站`、`🚚 状态更新`），对齐 OPPO 胶囊「最多 5 个字符」的官方口径。

真机实测（2026-09-28 固定盒后）：到站 / 取件完成 **均为 `118.7dp × 40dp`（356×120px，中心 x=539）**；
`取件码 77-77-77777` 白字全量无省略，33% 宽即够（未触发放宽），故三态仍严格等大。

**状态机是纯 Kotlin + 注入时钟**，只在主线程推进：`Handler.postDelayed` 按 `advanceAt` 挂一次延迟回调
（非轮询），数据读取在 IO。节奏：展开 260ms / 展开态 3000ms / 成功 1600ms（1~2s 自动消失）/ 收起 900ms。

**点胶囊进详情后整岛收工**：`IslandManager.onOverlayTapped()` 让状态机立刻 `hide()` 并撤掉 tick，
否则下一次 tick 会把胶囊在详情页上方「复活」一次（真机踩过，改前实测两次 `fg_duration=1034/1264`）。

真机验收记录见 `CHANGELOG.md` 2026-09-27「真机验收」：通知版不双发、悬浮版窗口 `appop=SYSTEM_ALERT_WINDOW`
+ 状态序列 `236ms / 2993ms` 精确对齐、点胶囊进详情、标记已取件后首页/Widget/详情三端一致。

### 3.8 快递短信读取（短信只是又一个数据来源）

依据：`D:\codex\快递短信功能需求开发.md`（短信 SOP）。**不接 AI、纯本地规则**。

**接入点在来源管理，不走旁路**：短信在 `rules.json` 里注册成一个来源（`packageName = com.parcelhub.sms`、
`appName = 短信`、`sourceType = OTHER`、`enabled = true`），`SourceSettings.seed()` 把它补进 `source_apps`，
于是它和其它来源**共用同一张开关表**（`SourceSettings.setEnabled` + 来源管理页）、**默认开**、重启后仍开；
`pipeline.gate / enqueue` 零改动复用，`ParserEngine` 认得这个 `sourceRule` 就跳过通用门禁
（SOP §3：短信只是一个数据来源，识别结果统一进既有 `Parcel` 数据层）。

```text
SmsReceiver（RECEIVE_SMS 受保护广播）
   └─ dispatchSms：goAsync() + appScope（冷进程也先读 source_apps 的真实开关，不用过期内存镜像）
        └─ SmsIngestor：开关 → 排除词（SOP §11）→ 物流关键词（SOP §10）→ smsHash 去重（§42）
             → RawNotification(sourcePackage=com.parcelhub.sms, notificationKey=hash)
                └─ IngestPipeline（与通知监听 / 分享导入同一条队列）
                     └─ ParserEngine → ShipmentMatcher → 首页 / 列表 / Widget / 灵动岛 / 自动查询
```

| 口径 | 做法 |
| --- | --- |
| 第一层过滤 | 先排除（验证码 / 银行 / 账户 / 转账 / 登录 …）再看物流词，**过不了就地丢弃**，不进库、不进 Widget、不进灵动岛（§9~§11 / §40）；`SmsReceiver` 只做「接收 → 提取 → 分发」，不查接口、不跑重活（§7/§8） |
| 去重 | `smsHash = sha256(sender\|body\|timestamp)` 直接当 `notificationKey`；`SmsSeenStore` 接口 + 内存实现（单测）/ `SharedPreferences` 最近 200 条（真机、跨重启）；多段短信按发件人合并、取最早时间戳（§42/§46） |
| 权限 | 只 `RECEIVE_SMS`；**不申请** `SEND_SMS` / `WRITE_SMS`，也不申请 `READ_SMS`（历史短信扫描本期未做，§5/§36）。入口在来源管理：点「短信」行的开关或副标题触发系统弹窗；被拒后副标题变「点这里打开系统权限设置」（§45），首启不强弹 |
| 隐私 | 短信原文不入库、不落日志（日志只记 `enabled / enqueued / skipped / reason`）；`notificationKey` 是哈希，`title` 是**发件人指纹**（`sha256(sender)` 截断 + 4 位分隔符，不可逆且不会被单号正则误认），手机号进不了 `parcel_events`；落库摘要沿用 `PrivacyUtil.summarize`（§38/§39） |
| 可插拔 | 来源管理一行开关即全部控制：关掉后广播仍收，但 `SmsIngestor` 直接返回 `reason=disabled`，一条都不入队（§6） |
| 建包门槛（§24 偏离） | SOP §24 **建议**给短信单独设建包门槛；本工程**沿用全局 `ShipmentMatcher.MIN_CREATE_CONFIDENCE = 0.40`**，避免同一文案在不同来源下表现不一致。该建议未实现，此处为有意偏离 |

**解析层两处通用增强**（顺带惠及所有来源）：

- `TrackingExtractor` 新增第 3 步：无标签、无承运商提示时，按**带字母公司代号**的单号形态反查承运商
  （`SF…` / `YT…` / `EMS…`）；纯数字形态多家共用，**一律不猜**（SOP §6.4）。对应短信 SOP §43 用例
  「您的快件 SF1234567890123 正在派送」→ `carrier=顺丰速运`。
- `rules.json` 的 `DELIVERED` 补门口投放关键词（`已放在家门口` / `已送到门口` 及其正则变体），
  使「您的包裹已放在家门口。」判得到 `DELIVERED`（§43 用例）；落库 `address=家门口`，仍被 `needsPickup`
  排除，**不进首页待取**（口径不变）。

**E2E 注入通道**（§44）：`SmsDebugReceiver`（action `com.parcelhub.DEBUG_SMS`）。
真机发不出 `SMS_RECEIVED`（受保护广播，shell 直接被 AMS `Permission Denial` 拒），故留一条等价入口，
走与 `SmsReceiver` **完全相同**的过滤 → 去重 → 入队 → 解析 → 落库链路。两道护栏：
Manifest `android:permission="android.permission.DUMP"`（只有 adb shell 这类系统调用方持有，三方应用拿不到）
+ 代码里 `BuildConfig.DEBUG` 判断。

```powershell
adb shell am broadcast -a com.parcelhub.DEBUG_SMS -n com.parcelhub/.sms.SmsDebugReceiver `
  --es sender 1069888000001 --es body "您的包裹已到驿站，取件码8-8-8888，运单号SF7788990011223"
# 重复投递验证：补 --el timestamp <同一时间戳> 再发一条 → reason=duplicate
```

真机验收（2026-09-28 / HONOR HLK-AL00 / Android 10）：默认开且重启仍开、`pm grant|revoke` 与副标题三态联动、
注入后 `shipments.source_platform = com.parcelhub.sms` 且列表 / Widget / 灵动岛 / 自动查询同步、
关开关后 `reason=disabled` 不入库、同信封重发 `reason=duplicate`。明细见 `CHANGELOG.md`。

---

## 4. 已修缺陷

| 缺陷 | 现象 | 修复 | 回归 |
| --- | --- | --- | --- |
| **详情页标「已取件」后角标不变** | 详情页点「标记已取件」后用户状态 / 取件时间都更新了，但右上角标仍是黄色「待取」 | 详情页 `StatusChip(statusEnum)` 漏传 `userStatus`，默认按平台状态渲染（列表页传了、详情页没传）→ 三处调用点补传；判定抽成纯函数 `chipStyleOf` / `chipLabelOf`（用户状态优先于平台状态） | 单测 `StatusChipTest`（11 条）；真机导入样本 → 详情页角标 待取(黄) → 点「标记已取件」→ 已取件(绿) |
| **菜鸟自动填单「填单超时」** | 系统日志 `reportViewIds=false flags=0x0`：无障碍拿不到 `viewIdResourceName`，扫描永远 miss → 5s 窗口超时 | `res/xml/cainiao_accessibility.xml` 补 `flagDefault\|flagReportViewIds`（期望 `flags=0x11`）；**需在系统设置重绑服务后复验 E2E** | 单测 `CainiaoAccessibilityConfigTest`（配置项回归）；**2026-09-28 重绑后已复验**：`[CAINIAO] accessibility connected reportViewIds=true flags=0x11`，干净复跑 E2E 全绿 |
| **无障碍服务无法开启（自动填单号整体失效）** | 系统「设置 → 无障碍 → 已安装的服务」里没有本服务；`settings put secure enabled_accessibility_services` 写进去也绑不上（`dumpsys accessibility` 的 `Bound services` 恒为空、`installedServiceCount` 少 1） | `AndroidManifest.xml` 里 intent-filter 的 action 写成了 `android.service.accessibility.AccessibilityService`（照 NotificationListener 格式抄错），改为标准值 **`android.accessibilityservice.AccessibilityService`**；顺带补 `android:label` | 真机：`installedServiceCount` 17→18，`Bound services` 出现 `快递聚合助手 · 自动填单号`（现显示名已改 → `树懒快递助手 · 自动填单号`）；系统设置页可见且「已开启」 |
| **派送中 / 运输中的快递点「已取件」后仍挂在首页** | 详情页标记已取件后，列表页「运输中」已移出、「已签收」已移入，但**首页「派送中 / 运输中」分组仍显示该包裹** | 首页分组原来只按平台状态过滤；新增 `isActiveOutForDelivery` / `isActiveInTransit`（排除 `PICKED_UP`），与列表页口径对齐 | 单测 `picked_up_out_for_delivery_leaves_home_groups` / `picked_up_in_transit_leaves_home_groups` |
| 「标记已取件」写库结果不可判定 | DAO 用 `@Update`（不返回行数），是否真的写进去只能靠 UI 猜 | DAO 新增 `markPickedUp` / `updateUserStatus`：`UPDATE ... WHERE id = :id` **返回受影响行数**；Repository `setUserStatus` 返回 `Boolean`（rows>0）；详情页写入失败弹 Toast「标记失败，请重试」，成功后详情页新增「取件时间」行 | 单测全通过；真机校验 `picked_up_at` 列实际写入 |
| App 误判无障碍「未开启」 | 系统里已开启，App 首页仍弹「开启自动填单号（可选）/ 去开启」，设置页显示无 ✓ | `PermissionUtil.isAccessibilityEnabled` 之前只按完整类名后缀匹配，而系统写回的是缩写组件名 `com.parcelhub/.autoquery.cainiao.CainiaoAccessibilityService`；改为两种写法都认 | 真机：首页引导卡片消失；设置页「自动填单号（无障碍）」显示 ✓ |
| 同驿站串单 | 真机复现：`YT1234567890123` 的到站事件被并进 `771234567890` | `ShipmentMatcher.matchByContext` 增加「两侧都有运单号且不同 → 跳过」守卫，保留单侧无单号的兜底 | `ShipmentMatcherTest.match_by_context_never_cross_merges_different_tracking_numbers` |
| 首页按钮尺寸不均 | 「复制取件码」占满剩余宽度且折行，另两个按钮很小 | 三按钮 `weight(1f)` 等宽 + 收窄 `contentPadding`，文案 `maxLines=1` | 真机 dump：三按钮 280/280/280 px，文案 211 px 完整不裁切 |
| 地点信息被行政区前缀撑长 | 取件地址一整串地址难以扫读 | 取件地址只保留站点，行政区前缀交给「放置位置」行 | `AddressExtractorTest` 4 条新用例 |
| **冒烟第 9 步「来源管理：菜鸟 / 顺丰速运」假失败** | `Has-Text-Scrolled` 默认只上滑 3 次，且**最后一次滑动之后不再检查**；来源管理 19 行 ≈ 5.3 屏，排在后面的行根本够不到 | 默认次数 3 → 5，循环改 `i -le tries`（每屏上滑后必查）；顺带补两条断言：「来源管理：短信」行存在、「短信开关默认开」（`Row-Switch` 必须用**当前屏**的 dump，不能用进页面时的旧 `$doc`） | 冒烟 **PASS=86 / FAIL=0**（84 → 86 项） |
| **冒烟 8b「打开权限引导页」假失败** | `Go-Tab` 后固定上滑两次就点「重新运行权限引导」；该入口在设置页里的位置会随分区增减漂移，两次滑动踩空 → `FAIL=设置页入口未找到`（连带跳过引导页里 6 条断言） | 改成**双向找**：先往顶（上滑）再往底（下滑）各最多 5 次，每次滑完重新 `uiautomator dump` 再查 | 复跑冒烟 **PASS=86 / FAIL=0 / exit=0** |

---

## 5. 测试清单

```
app/src/test/java/com/parcelhub/
  parser/   ParserCorpusTest（120 条语料验收）、ParserEngineTest、
            TrackingExtractorTest、PickupExtractorTest、AddressExtractorTest
  matcher/  ShipmentMatcherTest（含跨单号不归并）、StateMachineTest、DedupEngineTest
  ui/       ShipmentFilterTest（分组口径 / 已取件归入已签收 / 家门口不进待取 /
            首页派送中·运输中在已取件后剔除）、
            StatusChipTest（状态角标：用户状态优先于平台状态）
  widget/   TodoWidgetItemTest（谁能进待办 / 取件任务优先排序 / 待查询文案 /
            家门口与已取件被排除 / 勾选反馈保留一拍 / 勾选项按锚点插回原位）、
            TodoWidgetCapacityTest（一屏条数：版面公式 / 本机 184dp=6 条 / 无高度兜底 /
            封顶 / 行高含 +5px 行间距 / 页数与越界页码折叠 / 页起始下标）、
            TodoWidgetFeedbackTest（勾选反馈帧表：✓ pop 起手与回弹、淡出递减到 0、
            预滑进度同源、越界帧夹取、普通条目恒等帧）、
            EasingTest（三次贝塞尔：端点/单调性/牛顿法与二分一致）
  island/   IslandEventDispatcherTest（事件映射 / 重复不产事件 / eventId `7515_ARRIVED_20260927`）、
            IslandStateMachineTest（四态与时序：260/3000/1600/900ms 区间、成功态 1~2s 后消失）、
            IslandDedupTest（同日同事件只弹一次 / 跨类型 / 重启后仍去重 / 跨天清空）
  sms/      SmsDetectionTest（短信 SOP §43 六条语料：正常派送 / 驿站 / 家门口 / 验证码 /
            银行 / 只有手机号，含第一层过滤断言 + 白名单注册 + 发件人指纹不可逆）、
            SmsIngestorTest（关开关不入队 / 物流短信入队字段 / 同信封只入一次 / 时间戳不同算新信封 /
            排除与非物流不入队 / 队列满不记账下次重试 / 哈希不含原文与手机号）
  util/     PrivacyUtilTest（脱敏）
```

当前：**277 个单测全部通过，0 失败**（`.\gradlew.bat :app:testDebugUnitTest`；
数量按 `app\build\test-results\testDebugUnitTest\*.xml` 的 `tests` 汇总，29 个测试类）。

> 桌面 Widget 的真实渲染、点击与写库这一段**不在单测覆盖范围内**（需要真的把 Widget 加到桌面
> 并由系统 AppWidgetHost 渲染）。真机验证方式：桌面双指捏合 → 小组件 → 添加「快递待办」，
> 再用 uiautomator 读节点 / `adb shell dumpsys appwidget` 核对内容。
> 另外，App 进程被杀后 Widget 无法自行刷新（不做后台常驻），这是 App Widget 的固有边界。

#### 桌面 Widget 真机验收记录（2026-09-27）

| 场景 | 结果 |
| --- | --- |
| 初始渲染 | 标题「2 项待办」，两条分别为 `☐ 📦取件码 99-9-7515｜菜鸟驿站`、`☐ 待查询｜菜鸟驿站` + 单号小字 |
| 点左侧方框 | 1 秒内条目消失、标题变「1 项待办」；数据库 `id=1`：`user_status: UNPROCESSED → PICKED_UP`（平台 `status` 仍为 `ARRIVED`），`picked_up_at=1790484769609` |
| 点条目主体 | 打开 `MainActivity` 详情页，展示「已到站 / 取件码 77-7-4321 / 取件地址 菜鸟驿站」 |
| 全部完成后 | 标题与空态均显示「暂无待取快递」 |
| 家门口投递 | 不在待办中（`needsPickup` 排除） |
| 条目密度 | 上下 padding 由 7dp 压到 1.5dp（行高 162→118px、行间距 14→3dp），方框 40→36dp，一屏从 1 条变 3 条 |
| 容量与翻页 | 7 件待取样本 → 3 页：第 1 页 3 条 +「4 项没显示 · 点此翻页 ›」，第 2 页 3 条 +「1 项没显示…」，第 3 页 1 条 +「最后一页 · 点此回第一页 ›」；再点一次回第 1 页（循环取模） |
| 翻页字位置 | 与标题同行右上角（dump 对齐：title top 1120 / more top 1126，差 6px 视觉齐平），单页时隐藏（`GONE`，不占条目区高度） |
| 标题点击 | 点「N 项待办」打开 `MainActivity` |
| 点方框后自适应 | 7 → 6 件后自动从 3 页变 2 页，页码越界被折回（`page=1/2`），标题 −1、勾选反馈正常 |

---

## 6. V0.1 范围之外（留待后续）

- SOP Phase 7 异常场景全量、Phase 8 性能压测（1000 条通知 / P95 解析耗时 / PSS）。
- 规则热更新下载通道（`RuleManager.reload()` 已预留入口，未接网络）。
- **快递智能识别与自动查询（`EXPRESS_SMART_QUERY` SOP）**：`AutoQueryDecisionEngine` 决策优先级与 10 分钟冷却、
  `AutoQueryDecisionType` 四态、TC-001~TC-015，以及「菜鸟 → 小程序 → 网页」实联网查询链路。
  接入时需补 `INTERNET` 权限；隐私文案只声明「仅处理与快递相关的信息」，不承诺零网络。
- LSPosed / Root 增强采集源、云同步、OCR、AI。

---

## License

本项目由 **actionsk** 发布，采用 **Mozilla Public License 2.0（MPL-2.0）**。

Copyright © 2026 actionsk

你可以根据 MPL-2.0 的条款使用、修改和分发本项目。

对于受 MPL-2.0 覆盖的源码文件，如果你修改这些文件并对外发布修改后的版本，需要按照 MPL-2.0 的要求提供相应的源代码和许可证信息。

MPL-2.0 是文件级 Copyleft 许可证。独立新增且未包含本项目受保护代码的文件，不会因为与本项目一起使用就自动被要求采用 MPL-2.0。

完整许可证内容请参阅：

`LICENSE`

### 项目信息

| 项 | 值 |
| --- | --- |
| Project | 快递聚合助手（ParcelHub） |
| App 显示名 | 树懒快递助手 |
| Copyright | © 2026 actionsk |
| License | MPL-2.0 |
| SPDX | `SPDX-License-Identifier: MPL-2.0` |
| Maintainer | actionsk |

相关文件：[`LICENSE`](LICENSE)（MPL-2.0 正文）、[`NOTICE`](NOTICE)、
[`CONTRIBUTING.md`](CONTRIBUTING.md)、[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
第三方依赖许可证清单与核对方法见 `THIRD-PARTY-NOTICES.md`；仓库内不含 vendored 第三方源码。
