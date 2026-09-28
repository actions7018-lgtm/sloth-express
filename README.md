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
