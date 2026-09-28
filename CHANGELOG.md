# 变更记录

> 约定：**每完成一次改动，都必须同步更新本文档**（按日期倒序追加），
> 涉及架构 / 口径 / 构建环境的改动还要同步 `README.md` 对应章节。
> 格式：新增 / 修复 / 变更 / 测试。

---

## 2026-09-28

### 新增

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **短信读取（来源管理里的等一来源，默认开）** | `sms/SmsContract.kt`、`sms/SmsEnvelope.kt`、`sms/SmsSeenStore.kt`、`sms/SmsIngestor.kt`、`sms/SmsReceiver.kt`、`sms/SmsPermissionManager.kt`、`assets/rules.json`、`AndroidManifest.xml`、`ServiceLocator.kt`、`ui/source/SourceScreen.kt` | 按快递短信 SOP §3/§49：短信只当一个**数据来源**，不建第二套链路。`rules.json` 注册 `com.parcelhub.sms`（`appName=短信`、`sourceType=OTHER`、`enabled=true`）→ `SourceSettings.seed()` 补进 `source_apps`，开关的**唯一来源就是这张表**（与其它来源共用 `SourceSettings.setEnabled` / 来源管理页），默认开、重启仍开；`SmsReceiver` 只做「接收 → 提取 → 分发」（`goAsync` + `appScope`，冷进程也先读 `source_apps` 真实开关而非过期内存镜像）；`SmsIngestor` 顺序 = 开关 → 排除词（§11）→ 物流词（§10）→ `smsHash` 去重（§42）→ 构造 `RawNotification`（`sourcePackage=com.parcelhub.sms`、`notificationKey=hash`、`title=发件人指纹`）→ 复用 `IngestPipeline.enqueue`，`ParserEngine` 因 `sourceRule` 命中白名单跳过通用门禁；Manifest 加 `RECEIVE_SMS` 与 `SmsReceiver` 声明（**不加 receiver 级权限**：`SMS_RECEIVED` 本就是受保护广播，shell 实测被 AMS 直接拒）；来源管理页 SMS 行特判渲染，副标题三态（已授权 → 隐私文案 / 未授权 → 点这里授权 / 被拒 → 点这里去系统权限页），点开关或副标题即申请权限。与 SOP 的一处**有意偏离**：不给短信单建包门槛，沿用全局 `ShipmentMatcher.MIN_CREATE_CONFIDENCE = 0.40`（§24 只是「建议」，避免同文案不同源表现不一致） |
| `SmsDebugReceiver` E2E 注入通道（§44） | `sms/SmsDebugReceiver.kt`、`AndroidManifest.xml` | 真机发不出受保护广播 `SMS_RECEIVED`，留等价入口喂一条短信走**完全相同**的链路；护栏 = `android:permission="android.permission.DUMP"`（只有 adb shell 这类系统调用方持有）+ 代码 `BuildConfig.DEBUG` |
| 解析层：无标签单号按代号形态反查承运商 | `parser/TrackingExtractor.kt` | 新增第 3 步：无标签、无承运商提示时，只认**带字母公司代号**的形态（`SF…`/`YT…`/`EMS…`），纯数字多家共用一律不猜（§6.4 不得猜测）。使短信 §43 用例「SF1234567890123 正在派送」→ `carrier=顺丰速运`（已有语料样本全部带标签，不受影响） |
| 规则：门口投放判「已送达」 | `assets/rules.json` | `DELIVERED` 关键词补 `已放在家门口`/`已放到家门口`/`已送至家门口`/`已投递至家门口` + 正则 `已(放在\|放到\|投递至\|送至\|送到)\s*(家)?门口`；使 §43 用例「您的包裹已放在家门口。」判得到 `DELIVERED`（`address=家门口` 仍被 `needsPickup` 排除，首页/列表/Widget 口径不变） |
| **开源许可证与贡献机制（MPL-2.0）** | `LICENSE`（新建）、`NOTICE`（新建）、`CONTRIBUTING.md`（新建）、`THIRD-PARTY-NOTICES.md`（新建）、`README.md`、`app/src/**/*.kt`（116 个文件加版权头） | 目标许可证 **MPL-2.0**（SPDX `MPL-2.0`、版权主体 actionsk、年份 2026），**不是** Apache-2.0、原 README「许可证：MIT」是错误声明已一并改掉。① `LICENSE` = Mozilla 官方 `index.txt` 正文**一字不改**（17164 字节，含 Exhibit A/B），仅在正文之后追加独立的 `Project notice` 版权块 `Copyright © 2026 actionsk`（不改条款、不加「禁止商业使用」等额外限制）。② `NOTICE` 按模板：项目版权 + 第三方归属各自权利人。③ `CONTRIBUTING.md`：Fork → 分支 → 本地测试 → Commit → PR → **actionsk 审核**，并写明「MPL 只要求改了受覆盖文件并对外分发时提供对应源码，**不强制**向官方仓库提 PR」+ 敏感信息禁提清单。④ `THIRD-PARTY-NOTICES.md` **不猜许可证**：以 `gradlew :app:dependencies`（runtime 124 + test-only 4 个已解析构件）逐个读 Gradle 缓存 POM 的 `<licenses>`，POM 缺节点改读 jar 内 `LICENSE` 文件 → 结果 **Apache-2.0 占绝大多数**（AndroidX / Compose / Room / kotlinx-serialization / kotlinx-coroutines / kotlin-stdlib / jspecify / AGP 9.4.1 / Kotlin Gradle 插件 2.3.21 / KSP 2.3.7 / Gradle wrapper jar `META-INF/LICENSE`），测试期 `junit:junit:4.13.2 = EPL-1.0`、`org.hamcrest:hamcrest-core:1.3 = BSD`（读自 jar 内 `LICENSE.txt`），唯一未确认 `com.google.guava:listenablefuture:1.0` → **NEEDS_REVIEW**（POM 与 jar 均无许可证节点，按「禁止猜测」不填、在报告里列出）。⑤ 116 个 Kotlin 源文件（86 main + 30 test）统一加版权头 `Copyright © 2026 actionsk` + `SPDX-License-Identifier: MPL-2.0` + MPL 正文句——全部是 `package com.parcelhub` 的原创文件，仓库内**无 vendored 第三方源码**（仅 `gradle/wrapper/gradle-wrapper.jar` 一个二进制），未改任何业务逻辑。⑥ `.github/` 不重复建（根目录已有 `CONTRIBUTING.md`）；无 git 仓库 → `git init` 后推 GitHub |

### 变更

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **桌面启动图标换成树懒 logo（自适应）** | `AndroidManifest.xml`、`res/mipmap-{mdpi..xxxhdpi}/ic_launcher*.png`、`res/mipmap-anydpi-v26/ic_launcher.xml`、`res/values/colors.xml` | 用户提供的 `logo_sat_lightblue.svg`（1024×1024）经 headless Edge 渲染 1024 PNG，再 Pillow 出图：Android 8.0+ 走**自适应图标**（背景色 `#D6E9F7`，前景树懒图形按内容 bbox 放入 108dp 画布的 66dp 安全区、透明底），API 24/25 回退 `mipmap-*/ic_launcher.png` 传统方形；`android:icon` 由 `@drawable/ic_launcher` 改 `@mipmap/ic_launcher`。**通知 smallIcon 仍用 `drawable/ic_launcher.xml` 不动**（`AppNotificationManager`/`IslandNotificationManager` 共 8 处引用）。真机 HONOR 桌面实测：浅蓝圆角图标 + 树懒坐纸飞机 + 「树懒快递助手」label 正常 |
| **Release 签名配置（产出已签名 APK）** | `app/build.gradle.kts`、`keystore.properties`（新建）、`keystore/sloth-express-release.jks`（新建）、`.gitignore` | `signingConfigs.release` 读根目录 `keystore.properties`（RSA 2048、有效期 10000 天、`CN=actionsk, OU=ParcelHub`）；密钥与口令**不入库**（`.gitignore` 新增 `keystore.properties`/`*.jks`/`*.keystore`）；`assembleRelease` 首次跑 `lintVitalAnalyzeRelease` 要联网下 lint 工具，直连超时 → 临时加 `gradle.properties` 的 `systemProp.*.proxy*` 走本机代理，构建后已还原（**不提交**代理配置） |
| 短信权限口径 | `util/PermissionUtil.kt` | 类注释「V0.1 默认不申请短信」已失真 → 改为：`RECEIVE_SMS` 属于来源管理「短信」行的开关依赖，检查/申请/跳设置都在 `sms/SmsPermissionManager`；`SEND_SMS`/`WRITE_SMS`/`READ_SMS` 与定位、相机、麦克风、通讯录仍一律不申请 |
| 冒烟第 9 步滚动加固 | `tools/device-smoke.ps1` | `Has-Text-Scrolled` 默认 3 → 5 次上滑，循环改 `i -le tries`（最后一屏滑完**必须再查一次**，原实现最后一次滑动结果永不检查）；来源管理 19 行 ≈ 5.3 屏，旧实现够不到排在后面的菜鸟/顺丰 → 上一轮 2 条假 FAIL |
| 冒烟 8b「打开权限引导页」入口改双向查找 | `tools/device-smoke.ps1` | 原来在 `Go-Tab` 后固定上滑两次再点「重新运行权限引导」，入口在设置页里的位置会随分区增减漂移 → 2026-09-28 实测 `FAIL=设置页入口未找到`。改为**先往顶（上滑）再往底（下滑）各最多 5 次**，每次滑完重新 `uiautomator dump` 再查（同 `Has-Text-Scrolled` 的假失败加固）；复跑 **PASS=86 / FAIL=0 / exit=0** |
| **灵动岛胶囊配色改纯黑（OPPO 样）** | `island/DynamicIslandView.kt` | 用户给的 OPPO 流体云规范原文「**胶囊背景固定为黑色，不允许开发者自定义**」+「胶囊文案最多 5 个字符」。配色由「按 `IslandTone` 分三套浅色双色渐变」改为**纯黑 `#000000` + 纯白 14sp 加粗标题 + 70% 白副行**，去掉 1dp 描边；对 SOP §17「浅色」是**有意偏离**（§17 真正反对的「纯黑巨大胶囊 / 大面积遮挡顶部」不在此列，本体 36dp × ≤33% 屏宽）。真机截图核对：黑底白字、圆角完整、两行文案无省略号；尺寸复测含 6dp 阴影 `112.7dp × 38.3dp`，扣掉阴影仍是 `103dp × 36~38dp`。规范里「左右各一元素、默认右侧仅一个元素」按评估**不采纳**（右侧补元素会挤掉 5 字文案宽度）；`D:\codex\快递灵动岛功能开发SOP.md` §17 已同步改写为 OPPO 口径（风格 / 不建议 / Compact / Expanded / Success 示例，示例标题同步 `📦 快递到站`、`3 个待取`、副行只画两行） |
| **灵动岛胶囊按 OPPO 流体云尺寸重做** | `island/DynamicIslandView.kt`、`island/IslandOverlayService.kt`、`island/IslandManager.kt` | 原实现 `WRAP_CONTENT` 三行（padding 16/12dp、15/14/12sp、固定 24dp 圆角）高约 90dp，比 OPPO 胶囊高一大截。改为 **36dp 药丸**：`minimumHeight = 36dp`、圆角 = 实测高 ÷ 2（layout 监听 + 渲染后各校正一次，字号缩放也收得住）、内边距 水平 14dp / 垂直 3dp、标题 14sp 加粗 + 副行 11sp（两行均单行、超长省略）、宽度上限 = 屏宽 33%；窗口 y = 状态栏 + 4dp。副行由「`line1 · line2` 合并」改为**只放 `line1`**（胶囊只保留取件码等主信息，地址/单号看通知与详情页，合并后实测把取件码挤成了省略号）；标题精简到 ≤5 字（`📦 快递到站`、`🚚 状态更新`），对齐 OPPO 胶囊「最多 5 个字符」口径。真机实测（1080×2340 / 480dpi）：胶囊态 `103dp×36dp`、展开态 `103dp×38dp`，中心 x=539（屏心 540），到站 / 派送两态文案均无省略号 |
| **灵动岛固定盒：三态等大** | `island/DynamicIslandView.kt` | 用户要求「取件完成的组件大小要和第一个一样」。尺寸此前全靠文案撑：到站 `356×117px` / 取件完成 `356×117px` / 超长文案 `356×120px`（高差来自 emoji 与省略号 fallback 字体行高）。改**固定盒**：`onMeasure` 强制 `setMeasuredDimension(屏宽33%, 40dp)`，两行子 View 行盒固定（`TITLE_LINE_DP=17` / `BODY_LINE_DP=16` × `fontScale`），副行间距由 `padding` 改 `topMargin`（行盒保持纯文本盒）；三态真机实测**均为 `356×120px = 118.7dp×40dp`、中心 x=539** |
| **灵动岛单号不再打点（SUCCESS 与到站统一为完整单号）** | `island/IslandManager.kt` | 用户口径：「运输中 / 派送中点已取件，取件完成灵动岛会省略快递单号，待取的正常，统一下」。根因 = `buildContent` 里 `tracking = …?.let(::tail)`（`SF1234567890123 → SF1…223`，那个 `…` 就是「省略」）：到站态展示完整取件码、SUCCESS 态（无取件码时回退单号）展示脱敏单号 → 两套口径。删掉 `tail()`，岛上单号一律完整展示 —— 与详情页 `DetailRow("运单号", …)`、列表页一致；落库摘要脱敏仍走 `PrivacyUtil.summarize`（单测 `ParserCorpusTest` / `PrivacyUtilTest` 仍断言），仅**展示层**放开。真机实测：派送中单点「标记已取件」→ SUCCESS 胶囊 `SF100000000032` 全量白字无打点、`356×120px = 118.7dp×40dp` 等大；单测 277/29/0 |
| **灵动岛取件码永不省略 + 改回白色（撤销绿码）** | `island/DynamicIslandView.kt` | 用户追加口径：「取件码数字被省略了不要省略，实在不行适当加一两个像素，取件数字改回原来的白色」。① `line1View.ellipsize` 永久 `null`（所有态，不再区分 SUCCESS）；`fitSubText()` 先 11→8sp 按 0.5sp 收缩，到下限仍差几像素就按**实差**放宽盒宽（`boxWidth` 改 `var`，换内容先 `resetBoxWidth()` 复位、短文案不残留放宽量，硬顶 `MAX_WIDTH_RATIO_GROW = 0.40` 屏宽），`titleView/line1View.maxWidth` 随之同步。② 撤销中途引入的 SUCCESS 绿 `#4CD964`，副行统一 70% 白；标题（≤5 字）超长仍省略兜底。真机实测：到站 `取件码 77-77-77777`、SUCCESS 态均 **356×120px = 118.7dp×40dp**、白字（绿像素 0）、无省略号、33% 宽即够未触发放宽；单测 277/29/0 |
| **App 显示名改「树懒快递助手」** | `res/values/strings.xml`、`ui/onboarding/OnboardingScreen.kt`、`notification/AppNotificationManager.kt`、`ingest/ShareEntryActivity.kt`（注释）、`README.md` | 用户要求改名。`app_name`、两个系统服务 `android:label`（通知采集 / 自动填单号）、Widget「打开…」、引导页标题、通知正文「去系统设置里打开“… · 自动填单号”」、分享入口注释、README 标题与首段统一改**树懒快递助手**；包名 `com.parcelhub`、`rootProject.name = ParcelHub`、数据库与全部业务逻辑**不动**（授权文案「仅处理与快递相关的信息」不变）。版权主体 / 项目名仍为「快递聚合助手 · actionsk」（`LICENSE` / `NOTICE` / `CONTRIBUTING` 按任务书固定），README 里以「Project: 快递聚合助手（ParcelHub） / App 显示名: 树懒快递助手」并列说明。冒烟不依赖应用显示名（断言的是「开始使用」「自动填单号（无障碍）」等既有文案） |
| README 补开源说明 | `README.md` | 顶部改为 **License: MPL-2.0 · Maintainer: actionsk**（原「许可证：MIT」删除）；文末新增 `## License` 段（MPL-2.0 使用 / 修改 / 分发与文件级 Copyleft 说明、`Copyright © 2026 actionsk`、指向 `LICENSE`）+ 项目信息表 + `NOTICE` / `CONTRIBUTING` / `THIRD-PARTY-NOTICES` 索引；原有章节一条未删 |

### 测试

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| Release 打包 + 单测复跑 | `app/build/outputs/apk/release/app-release.apk` | `:app:testDebugUnitTest` **277 tests / 29 classes / 0 failed**；`assembleRelease` BUILD SUCCESSFUL，`app-release.apk` 13,405,015 B，`apksigner verify --print-certs` **exit=0**、Signer `CN=actionsk, OU=ParcelHub, O=actionsk` |
| 短信单测 16 条 | `test/.../sms/SmsDetectionTest.kt`（8）、`sms/SmsIngestorTest.kt`（8） | §43 六条必测语料全绿（正常派送带单号+承运商 / 驿站带取件码 / 家门口 `DELIVERED` + `destination=家门口` / 验证码排除 / 银行排除 / 只有手机号不识别），另含白名单注册、发件人指纹不可逆；入队器覆盖 关开关不入队、入队字段正确、同信封只入一次、时间戳不同算新信封、排除与非物流不入队、队列满不记账可重试、哈希不含原文与手机号。**277 测试 / 29 类 / 0 失败** |
| 真机验收（HONOR HLK-AL00 / Android 10） | — | ① 来源管理「短信」行默认开，按 `ORDER BY app_name` 落在 申通 ↔ 菜鸟 之间（中通仍第一，不破冒烟断言）；② 点副标题 / 开开关 → `RECEIVE_SMS` 系统弹窗，允许后副标题切「读到的物流短信只在本机解析，短信原文不保存」；③ `pm revoke` + 冷启动 → 开关仍开（持久化）、副标题回到授权提示；④ 注入 → `sms ingest: enabled=true enqueued=1` → DB `shipments.source_platform = com.parcelhub.sms`、`parcel_events.source_package = com.parcelhub.sms`、`confidence=0.99`，列表出现「顺丰速运 / 已到站 / 取件码 8-8-8888 / 单号 SF7788990011223」，Widget 2→3 项，灵动岛 `COMPACT→EXPANDED→COMPACT`，自动查询 `[DECISION] … NOT_REQUIRED`；⑤ 关开关后注入 → `enabled=false enqueued=0 reason=disabled`，不入库；⑥ 同信封重发（同 `--el timestamp`）→ `enqueued=0 reason=duplicate` |
| 冒烟 | `tools/device-smoke.ps1` | **PASS=86 / FAIL=0**（84 → 86：新增「来源管理：短信」「来源管理：短信开关默认开」），上一轮菜鸟/顺丰两条假失败随滚动加固消失；8b 入口双向查找修复后 + OPPO 黑胶囊 APK 各复跑一次，均 **86 / 0 / exit=0** |
| 菜鸟自动填单 E2E 复验（上轮遗留） | 真机手工链路 | `settings delete secure enabled_accessibility_services` 解绑 → 前台重新写回绑定 → logcat 实测 `[CAINIAO] accessibility connected reportViewIds=true flags=0x11`（09-26 的 `flagReportViewIds` 修复已生效）；随后走完整链路：注入派送中单号 → `[QUERY_TASK] created` → 通知栏「打开菜鸟并填单号」→ `launch Opened(com.cainiao.wireless)` → `search entry click=true` → `INPUT_FOUND` → `set text` + `text verified` → `NUMBER_FILLED` → **`COMPLETED`**，截图核对菜鸟搜索框内单号无误 |
| 批量注入 50 条模拟短信 | `SmsDebugReceiver`（注入通道） | 按用户要求灌 50 条多样本（20 到站带唯一取件码 / 10 顺丰派送 / 10 韵达到配送点 / 10 申通签收，发件人 1069xxx / 95338 / 95311 / 95543），`am broadcast` 50/50 `result=0`；入库后拉 `parcelhub.db` 统计 **47 行**：`ARRIVED=2 / PICKUP_READY=12 / OUT_FOR_DELIVERY=12 / IN_TRANSIT=10 / DELIVERED=10 / UNKNOWN=1`，首页 待取 9 / 派送中 10 / 运输中 10（部分到站短信按匹配置信度并入已有单，符合 `MIN_CREATE_CONFIDENCE=0.40` 口径） |
| 开源许可证 + 改名轮构建与冒烟 | `tools/device-smoke.ps1` | 版权头 116 文件 + LICENSE/NOTICE/CONTRIBUTING/THIRD-PARTY-NOTICES + 显示名改「树懒快递助手」后：`gradlew :app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**、**277 tests / 29 classes / 0 failed**；真机冒烟 **PASS=86 / FAIL=0 / exit=0**（改名未踩任何文案断言）。冒烟会 `pm clear`，之后已恢复悬浮开关（`appops allow` + `parcelhub_island.xml`）并重灌 50 条 → DB **44 行**（`ARRIVED=2 / PICKUP_READY=11 / OUT_FOR_DELIVERY=11 / IN_TRANSIT=9 / DELIVERED=10 / UNKNOWN=1`）。另：`tools/*.ps1` 用法示例里的真机序列号改成占位符 `0123456789ABCDEF`（公开仓库不带设备指纹） |

---

## 2026-09-27

### 修复

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **详情页点「标记已取件」后，右上角标仍是黄色「待取」** | `ui/shipment/ShipmentDetailScreen.kt`、`ui/components/Components.kt`、`ui/home/HomeScreen.kt` | 详情页调用 `StatusChip(statusEnum)` 时**漏传 `userStatus`**，默认按 `UNPROCESSED` 走平台状态（列表页传了、详情页没传，两处口径不一致）。三处调用点全部补传；`StatusChip` 的判定/文案抽成纯函数 `chipStyleOf` / `chipLabelOf`（用户状态优先于平台状态，SOP §4.3），渲染只负责配色 |
| **无障碍服务无法开启（自动填单号整体失效）** | `AndroidManifest.xml` | intent-filter 的 action 写成了 `android.service.accessibility.AccessibilityService`（照 NotificationListener 格式抄错），改为标准值 `android.accessibilityservice.AccessibilityService`；补 `android:label`。此前系统压根不收录本服务，永远无法开启 |
| App 误判无障碍「未开启」 | `util/PermissionUtil.kt` | 系统写回的是缩写组件名 `com.parcelhub/.autoquery.cainiao.CainiaoAccessibilityService`，原来只按完整类名后缀匹配 → 改为两种写法都认 |
| 派送中 / 运输中标「已取件」后仍挂在首页 | `data/entity/ShipmentEntity.kt`、`ui/home/HomeScreen.kt` | 首页分组原来只按平台状态过滤，没排除 `PICKED_UP`（列表页早就排除了，两处口径不一致）。新增 `isActiveOutForDelivery` / `isActiveInTransit` |
| 「标记已取件」写库结果不可判定 | `data/db/Daos.kt`、`data/repository/ShipmentRepository.kt`、`ui/shipment/ShipmentDetailScreen.kt` | DAO 原用 `@Update`（不返回行数）→ 改为 `UPDATE ... WHERE id = :id` **返回受影响行数**；`setUserStatus` 返回 `Boolean`（rows>0 才算成功）；失败弹 Toast「标记失败，请重试」，不假装成功 |
| **菜鸟自动填单「填单超时」** | `res/xml/cainiao_accessibility.xml` | 系统日志实测 `reportViewIds=false flags=0x0`：配置文件缺 `flagReportViewIds`，无障碍拿不到 `viewIdResourceName` → 扫描永远 miss → 5s 窗口超时。补 `flagDefault\|flagReportViewIds`（期望 `flags=0x11`）；**2026-09-28 重绑复验通过**：`[CAINIAO] accessibility connected reportViewIds=true flags=0x11`，干净复跑 E2E `launch → search entry click → INPUT_FOUND → text verified → NUMBER_FILLED → COMPLETED`，菜鸟搜索框实测填入单号 |
| **Widget 点方框没反应** | `widget/TodoWidgetProvider.kt`、`res/layout/widget_todo.xml`、删除 `widget/TodoWidgetService.kt` | 集合 Widget 的 item 内子视图点击在这台设备（荣耀/华为桌面）上不生效，只有根视图点击被处理 → "点方框完成"被"点条目看详情"抢走。改为固定布局 + `RemoteViews.addView` |
| **Widget 加载失败、整块变空白** | `res/layout/widget_todo.xml` | 曾把条目区塞进 `ScrollView` 求真滚动，`RemoteViews` **白名单不放它** → `apply()` 抛 `Class not allowed to be inflated`，桌面弹「加载窗口小工具时出现问题」。回退为普通 `LinearLayout`（`widget_page`），超出一屏改用翻页 |
| **Widget 每页只显示 1 条、下面一大片空白** | `widget/TodoWidgetProvider.kt`、`widget/TodoWidgetCapacity.kt` | 容量按 `OPTION_APPWIDGET_MIN_HEIGHT`（132dp，「可缩到的最小值」）算 → 1 条。改为 `OPTION_APPWIDGET_MAX_HEIGHT`（184dp，本机实测框高 191dp）→ 3 条/页（**当时的**版面账，后随「方框压到 24dp」「行间距 +5px」两轮调整为 6 条/页，见下方变更）；并补 `onAppWidgetOptionsChanged` 回调 |
| **Widget 翻页回不到第一页（点多少次都停在末页）** | `widget/TodoWidgetProvider.kt` | 原来只把页码 +1 交给渲染层 `clampPage`，末页 +1 又被夹回末页。改为 `flipPage` 自己算 `(clampPage(current) + 1) % pages` 取模循环 |
| **Widget 条目间隔过大** | `res/layout/widget_todo_item.xml`、`widget_todo.xml` | 条目上下 padding 7dp → 1.5dp（行高 162 → 118px、行间距 14 → 3dp），方框 40 → 36dp；一屏从 1 条变 3 条 |
| **Widget 一点方框打勾就报「加载窗口小工具时出现问题」、整块组件变空** | `widget/TodoWidgetProvider.kt`、`widget/TodoWidgetFeedback.kt` | 根因：`RemoteViews.setFloat/setInt` 只是反射入口，目标方法还必须带 `@RemotableViewMethod` 注解，否则桌面 `apply()` 抛 `ActionException`。打勾动画用了 `View.setScaleX/setScaleY` → logcat `view: huawei.android.widget.ImageView can't use method with RemoteViews: setScaleX(float)` + `W/AppWidgetHostView: Error inflating RemoteViews`，桌面弹「加载窗口小工具时出现问题」（复现：截图正常 → tap → 组件消失 + toast，18 帧渲染中只有前两帧被应用）。改用白名单放行的通道重写动画：`TextView.setTextColor` / `ImageView.setImageAlpha` 做淡出、**不走反射**的 `RemoteViews.setViewPadding` 做 ✓ pop（改方框内边距）与预滑（本行设负上内边距）。`View.setAlpha / setScaleX / setTranslationY` 三个对照 AOSP Android 10 源码确认均无注解 |
| 诊断页残留临时探针 | `ui/diagnostics/DiagnosticsScreen.kt` | 删掉 `[A11Y_PROBE]` 两段一次性探针（定位无障碍服务未收录用，已定位完毕：Manifest action 写错，见上） |

### 新增

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **快递灵动岛（Dynamic Island）** | `island/*`（`IslandEvent`、`IslandState`、`IslandEventDispatcher`、`IslandStateMachine`、`IslandDedup`、`IslandNotificationManager`、`DynamicIslandView`、`IslandOverlayService`、`IslandManager`）+ `ServiceLocator.kt`、`ingest/IngestPipeline.kt`、`data/repository/ShipmentRepository.kt`、`ui/settings/SettingsScreen.kt`、`util/PermissionUtil.kt`、`AndroidManifest.xml` | 按《快递灵动岛功能开发 SOP》实现，**复用现有数据层**（不建第二套数据库、不为灵动岛建轮询服务）：`Repository → 状态机 → Dispatcher → 首页/Widget/灵动岛` 一条链。事件 `Arrived/Delivering/PickedUp/StatusChanged` 只带 `shipmentId`，接入点两处——入库 `IngestPipeline.process`（灵动岛接管则不再发普通提醒，避免双弹）、取件 `ShipmentRepository.onUserStatusChanged`（首页/详情/Widget 三个写入口共用一个 choke point，**写库成功才回调**）。状态机 `HIDDEN→COMPACT→EXPANDED→SUCCESS→COMPACT→HIDDEN`（纯 Kotlin + 注入时钟，只在主线程推进，`postDelayed` 按 `advanceAt` 挂一次，非轮询；展开 260ms / 展开态 3000ms / 成功 1600ms / 收起 900ms）；防重复 `shipmentId_事件_日期桶`（如 `11_ARRIVED_20260927`），`IslandSeenStore` 内存实现供测试、`SharedPreferencesSeenStore` 跨重启 + `rollDay` 跨天清空。双模式：默认**通知版**（固定 id `0x151A17` 新事件覆盖旧的、`setAutoCancel` 点击进详情、SUCCESS 2s 自动 cancel）；「悬浮显示」需用户主动授权，胶囊由 `IslandOverlayService` 托管（channel `island_overlay`，O+ `startForeground` 即刻应答，胶囊消失 `stopForeground(true)+stopSelf`，任何失败只记日志并回退通知）。视图用原生 View（悬浮窗口无 LifecycleOwner，ComposeView 易崩），背景 `GradientDrawable` 程序化生成不加 res 资源。Manifest 新增 `SYSTEM_ALERT_WINDOW` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` 与 `specialUse` 类型服务 |
| **点击悬浮胶囊后整岛收工** | `island/IslandManager.kt`、`island/IslandOverlayService.kt` | 真机发现：点胶囊 → 服务收起，但状态机还停在 EXPANDED，下一次 tick 会重新拉起前台服务，胶囊在详情页上方「复活」一次（实测两次 `fg_duration=1034/1264`）。加 `IslandManager.onOverlayTapped()`：点击后立刻 `machine.hide()` + 撤掉 tick 与通知 |
| `picked_up_at` 字段 | `data/entity/ShipmentEntity.kt`、`data/db/AppDatabase.kt` | 记录用户点「已取件」的时间；数据库 `version 2 → 3`，`MIGRATION_2_3` 用 `ALTER TABLE` 加列，不丢数据 |
| 详情页「取件时间」行 | `ui/shipment/ShipmentDetailScreen.kt` | 已取件时展示 |
| 家门口投递不计入待取 | `data/entity/ShipmentEntity.kt` | 新增 `isDeliveredToDoor`（复用 `PlacementClassifier`）；`needsPickup` 排除它。首页待取 / 列表待取 / Widget 三处共用同一口径 |
| **桌面小组件「快递待办」** | `widget/*`（`TodoWidgetProvider`、`TodoWidgetData`、`TodoWidgetItem`）+ `res/layout|xml|drawable|values` | 详见 `README.md` §3.6：固定布局 + addView，方框=完成、条目=详情，零轮询刷新，空态「暂无待取快递」，深浅色两套配色 |
| Widget 条目点击命中区域 | `res/layout/widget_todo_item.xml` | 方框从 22dp 放大到 40dp（图标视觉不变，padding 撑开），避免点不中 |
| `goAsync()` 兜住广播生命周期 | `widget/TodoWidgetProvider.kt` | "1.2s 勾选反馈 + 二次刷新"期间进程可能被回收，条目会卡在勾选态不消失 |
| **Widget 超出一屏的翻页查看** | `widget/TodoWidgetProvider.kt`、`widget/TodoWidgetCapacity.kt`、`res/layout/widget_todo.xml` | 容量纯函数 `rowsFor / pageCount / clampPage / startOf`（版面公式，改布局必须同步）；页码存私有 prefs（`widget_todo` / `page`）；翻页字放**标题行右上角**（与「N 项待办」同行、单页时 `GONE`），文案「N 项没显示 · 点此翻页 ›」/ 末页「最后一页 · 点此回第一页 ›」；`ACTION_NEXT_PAGE` 广播驱动 |
| Widget 标题点击进 App | `widget/TodoWidgetProvider.kt`、`res/values/strings.xml` | 底部翻页行挪到标题行后，标题点击 = 打开 `MainActivity`（`widget_todo_open_app` contentDescription） |
| `TodoWidgetCapacityTest` | `test/.../widget/TodoWidgetCapacityTest.kt` | 8 条：版面公式、本机 184dp = 3 条、无高度兜底 1 条、封顶 12、页数与越界页码折叠、页起始下标 |
| **20 条模拟快递造数脚本** | `tools/push-todo-samples.ps1` | 一键灌数并自动校验，覆盖「三类投放场景 + 首页三个分组」：站点 8 + 代收点 8（首页待取 16）+ 家门口 2（口径外，不进首页待取与 Widget）+ 派送中 1 + 运输中 1；`-Fresh` 清库重灌。校验链：首页 `待取（16）/派送中（1）/运输中（1）` → 列表 `取件码 9901/9902` → 详情 `放置位置 = 家门口` → 桌面 Widget `16 项待办`（6 条/页 × 3 页）。脚本内置 5 处真机坑处理（正则 Match 计数、`am start -S` 冷启动、导航精确匹配、下滑找分组、引导页跳过），见 README §3.3 |
| **Widget 勾选反馈逐帧动画** | `widget/TodoWidgetFeedback.kt`、`widget/Easing.kt`、`widget/TodoWidgetProvider.kt` | 用户反馈「打勾后要看到打勾标志、整条要有消失动画，不能直接消失」「动画不够丝滑，参考 UI 动画库」→ 拆成 ~30fps 帧序列（`STEP_MS=33`，17 帧 ≈ 0.6s）：进入 7 帧 ✓ pop（backOut 缓动、overshoot=4）、退出 10 帧按 Material standard 曲线同时驱动「整行淡出」与「下面条目上滑」，末帧移除时布局前移恰好等于预滑位移 → **收尾不跳**。缓动是自研 `Easing.cubicBezier`（牛顿法 + 二分兜底，同 CSS `cubic-bezier`），恒等帧零 setter、帧距按 `elapsedRealtime` deadline 对齐、动画期间 `requestRefresh` 直接返回 |
| **勾选项原地淡出（不先重排整页）** | `widget/TodoWidgetItem.kt`、`TodoWidgetData.kt`、`TodoWidgetProvider.kt` | 原实现把刚完成的条目 `feedback + ordered` **插到列表最前**（写库把 `updated_at` 顶到最新、且它已不满足 `needsPickup`），勾选非首行时整页先重排一次再淡出，动画全废。改为**写库前**先记下它当时的位置存进 `anchors`，渲染时按锚点插回原位（越界夹取；查不到才退回排最前）；页码也只在没锚到时才拉回第 1 页，否则条目留在用户点勾的那一页**就地**淡出 |
| `values/dimens.xml` | `res/values/dimens.xml` | `widget_item_pad_top/bottom`：单条上下内边距的唯一来源，布局与 `buildItem` 的预滑都读它（改一处即两处同步，否则预滑会算错位移） |

### 变更

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **隐私说明收敛为一句范围口径** | `ui/settings/SettingsScreen.kt`、`ui/onboarding/OnboardingScreen.kt` | 用户口径：隐私说明只保留「**仅处理与快递相关的信息**」，其余逐条列举（不读取短信 / 不申请定位、相机… / 本机解析仅查询物流时联网 / 日志脱敏…）**全部删除**；引导页「隐私承诺」同步改同一句。原因：逐条承诺随功能演进必然失真（下一步短信自动识别 SOP 会让「不读取短信」变成假话） |
| 冒烟：隐私文案与灵动岛区块断言 | `tools/device-smoke.ps1` | 设置页/引导页文案断言由「仅在查询物流时联网」改为「仅处理与快递相关的信息」，并断言**不含**旧口径（「不发起任何网络请求」「不读取短信」）；设置页新增「灵动岛」区块 2 条断言（总开关默认开、悬浮显示默认关），且隐私/版本两行被新区块挤到折叠区 → 改为有界滚动查找。合计 82 → **84 项** |
| 构建内存下调 | `gradle.properties` | `org.gradle.jvmargs` 3072m→1536m、新增 `kotlin.daemon.jvmargs=-Xmx1024m`。原配置在本机（闲时可用内存约 3GB）会让 `compileDebugKotlin` 阶段进程被系统杀掉，表现为无报错、耗时固定 2 分钟 |
| 冒烟第 12 步口径对齐 | `tools/device-smoke.ps1` | 「首页 2 张卡都有放置位置」与 App 口径冲突：**家门口投递不进首页待取**（`needsPickup` 排除 DOOR），首页只会看到站点样本 1 行。改为：首页断言 ≥1 行 + **首页不出现家门口卡（口径回归）**，家门口样本改到详情页断言「放置位置 = 家门口」，断言后回首页再进第 13 步 |
| 冒烟脚本修复 + 扩展 | `tools/device-smoke.ps1` | ①无障碍开关必须在 App 前台时写且用缩写组件名（否则被 ROM 回收），并挪到第 13 步（第 0 步 `am start` 会打乱首启状态）②切筛选后加 `Wait 2` 等 Room Flow ③返回栈改为有界循环 ④第 13 步新增无障碍系统绑定与诊断页断言 |
| 冒烟导航防雪崩 | `tools/device-smoke.ps1` | 第 7 步 BACK 后若落在别的 Tab，后续步骤会「入口找不到」连锁失败（PASS=52/FAIL=26 雪崩，非 App 缺陷）。新增 `Go-Tab $tab $mustHave` 兜底（最多 4 步纠偏 + 子串匹配），套在第 7/8/8b/9/10 步，并断言「BACK 后回到列表页」 |
| 冒烟放置位置断言 | `tools/device-smoke.ps1` | 固定次数上滑会滑过头/被横幅顶下去 → 改为**从顶部有界上滑查找站点卡**（滑到看见为止，有滑动上限） |
| Widget 日志归口 | `widget/TodoWidgetProvider.kt` | 临时 `Log.d(TAG, …)` 探针改 `AppLog.d("widget height=… rows=… page=…/… items=…")`（SOP §19.2 统一出口，release 不打 debug 日志）；删除未使用的 `TAG` |
| **取件码行间距 +5px（一页仍 6 条）** | `res/values/dimens.xml`、`widget_todo_item.xml`、`widget_todo.xml`、`widget/TodoWidgetCapacity.kt` | 用户口径确认为「**合计** +5px」：单条行高 72 → 77px（`0.83dp + 1dp`，3x 取整 2px+3px；拆两个值凑出 dp 表示不了的奇数 5px）。可用余量只有 15px，所以固定开销同步从 35dp 压到 **29dp**（上 8→5dp、下 6→4dp、到列表间距 2→1dp），版面公式 `(高度−29)/25.7` 仍算出 **6 条/页**（29+6×25.7=183.2 ≤ 184）。`ROW_H_DP` 由整数改小数（否则整数除法会多算），新增 `ROW_CONTENT_H_DP=24` 供预滑按**像素**取行高 |
| 造数脚本滑动起点 | `tools/push-todo-samples.ps1` | 翻页 swipe 起点 `y=1200` → `y=1850`：起点压在小组件上会把**组件拖走**或弹「窗口小工具」选择器（真机踩过）；另在脚本头补「校验是绝对计数、必须带 `-Fresh`」的说明（3 项 FAIL 即因此，非缺陷） |

### 测试

- 新增 `widget/TodoWidgetItemTest`（11 条）：谁能进待办、取件任务优先排序、待查询文案、家门口/已取件被排除、勾选反馈保留一拍、**锚点插回原位 / 越界夹取 / 无锚点兜底排最前**
- 新增 `ShipmentFilterTest`（5 条）：家门口不进待取、"驿站门口"不误伤、首页派送中/运输中在已取件后剔除
- 新增 `StatusChipTest`（11 条）：角标口径回归——已到站/待取件/已签收 + 已取件一律显示「已取件」，未操作时仍按平台状态显示（本次缺陷的直接回归用例）
- 新增 `widget/TodoWidgetCapacityTest`（10 条）：一屏条数公式、本机 184dp = 6 条、无高度兜底、封顶 12、行高含 +5px 行间距、页数与越界页码折叠、页起始下标
- 新增 `widget/TodoWidgetFeedbackTest`（勾选反馈帧表）：✓ pop 起手与回弹到静止内边距、进入段不淡出不预滑、退出段 alpha 严格递减到 0、预滑进度与淡出同源、越界帧夹取、普通条目恒等帧
- 新增 `widget/EasingTest`（三次贝塞尔缓动）：端点、单调性、牛顿法与二分兜底一致、standard/decelerate/accelerate/backOut 曲线形状
- 新增 `island/IslandEventDispatcherTest`（12 条）：入库/用户操作 → 事件映射，到站需「到站态 + 有取件码 + 首次发现」、重复到站/运输中变化不产事件、`DELIVERED/RETURNED/CANCELLED` 走 `StatusChanged`、标记成功才产 `PickedUp`、**事件 id 格式 `7515_ARRIVED_20260927`**
- 新增 `island/IslandStateMachineTest`（9 条）：四态推进与时序（展开 260ms、展开态 3000ms、成功 1600ms、收起 900ms 均断言在 200~3000ms 区间内）、成功态 1~2s 后自动消失、`hide()` 后不再推进
- 新增 `island/IslandDedupTest`（6 条）：同一天同事件只弹一次、不同事件类型互不挤占、**重启后仍去重**（`SharedPreferencesSeenStore` 序列化回读）、跨天 `rollDay` 清空
- 当前 **261 个单测全部通过**（27 个测试类）

### 真机验收（荣耀 HLK-AL00 / Android 10）

- 无障碍：`installedServiceCount` 17→18，`Bound services` 出现本服务，系统设置页可见且「已开启」
- 派送中标已取件：数据库 `user_status: UNPROCESSED → PICKED_UP`、`picked_up_at=1790480763079`；首页派送中 1→0；快递页「已签收」出现该条；`force-stop` 重启后仍保持
- Widget：点方框 1 秒内条目消失、标题「2 项待办」→「1 项待办」；点条目打开详情页；全部完成后显示「暂无待取快递」
- Widget 翻页（7 件待取样本，**当时为 3 条/页的版面**）：3 条/页 × 3 页，「4 项没显示 · 点此翻页 ›」→「1 项没显示…」→「最后一页 · 点此回第一页 ›」→ 回第 1 页（循环取模）；翻页字与标题同行右上角（top 差 6px 视觉齐平），单页时隐藏；标题点击进 App；点方框后 7→6 件、页码越界自动折回 `page=1/2`
- 20 条模拟快递（`tools\push-todo-samples.ps1`，**当时为 3 条/页的版面**）：首页 `待取（16）/派送中（1）/运输中（1）`；列表见 `取件码 9901/9902`、详情 `放置位置 = 家门口`；Widget `16 项待办` = 6 页 × 3 条（末页 1 条）；三类投放场景与首页三个分组均覆盖，脚本校验 8/8 全通过
- **灵动岛·通知模式**：导入到站样本 → 只发 1 条 `channel=island`（普通提醒被接管，不双发）；进派送样本 → 「🚚 … 正在派送」；同状态重复导入不再弹
- **灵动岛·悬浮模式**：设置页开「悬浮显示」后导入新到站 → `dumpsys window` 出现 `Window{… com.parcelhub} appop=SYSTEM_ALERT_WINDOW`（胶囊真的盖在桌面上），状态序列 `COMPACT(+236ms) → EXPANDED → +2993ms → COMPACT → 消失`，前台服务 `fg_duration=4421ms`（= 260+3000+900+260），系统「显示在其他应用上层」通知随胶囊出现/消失；截图像素核对：顶部居中圆角浅色卡（x 322~757，中心 x=540）
- **灵动岛·点胶囊进详情**：点后打开对应运单详情页，且**不再复活**（改前会再起一次服务 `fg_duration=1264`）
- **灵动岛·标记已取件三端联动**：详情点「标记已取件」→ DB `已取件 + 取件时间`、首页 `待取（4）→（3）`、Widget `items=4→3`、SUCCESS 胶囊 `1600ms` 后收起；`force-stop` 重开仍为已取件
- 隐私文案：设置页与引导页均只展示「仅处理与快递相关的信息」；冒烟 **84/84 全绿**
