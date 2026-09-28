# Third-Party Notices

本项目可能使用第三方开源软件、SDK、库、代码和其他资源。

第三方软件的版权归其原作者或相应权利人所有。

第三方组件继续受其各自许可证约束。

本项目自身采用 MPL-2.0（见 `LICENSE`）。整体采用 MPL-2.0 **不会**改变下列第三方组件的
许可证：Apache-2.0 / EPL-1.0 / BSD 等组件继续按各自条款适用，并须保留各自的版权声明与
许可证文本。不要把第三方代码标记为 actionsk 原创代码，也不要把第三方依赖改写成 MPL-2.0。

**核对方式（不猜测）**：以 Gradle 解析出的依赖树为准
（`gradlew :app:dependencies --configuration debugRuntimeClasspath` / `debugUnitTestRuntimeClasspath`），
逐个构件读取本地 Gradle 缓存 `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/*.pom`
里的 `<licenses><license><name>`；POM 无许可证节点时改读构件 jar 内的 `LICENSE` 文件。
仍未确认的一律标 `NEEDS_REVIEW`，见文末表格。

## Components

### 运行时依赖（直接声明，打进 APK）

| Component | Version | License | Source |
|---|---|---|---|
| `androidx.compose:compose-bom` | 2026.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| `androidx.compose.ui:ui` / `ui-graphics` / `ui-tooling-preview` / `ui-tooling`（debug） | 1.12.1（BOM 解析） | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.compose.foundation:foundation` | 1.12.1（BOM 解析） | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.compose.material3:material3` | 1.4.0（BOM 解析） | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.compose.material:material-icons-extended` | 1.7.8（BOM 解析） | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.core:core-ktx` | 1.18.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.navigation:navigation-compose` | 2.10.2 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.lifecycle:lifecycle-viewmodel-compose` / `lifecycle-runtime-compose` | 2.11.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `androidx.room:room-runtime` / `room-ktx` | 2.8.5 | Apache-2.0 | https://developer.android.com/training/data-storage/room |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlin:kotlin-stdlib`（随 Kotlin 插件带入） | 2.3.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |

### 运行时传递依赖（按 group 汇总，共 124 个已解析构件）

下列构件的许可证均直接读自各自 POM 的 `<licenses>` 节点（Apache-2.0），未做任何推断；
唯一的例外列在文末 NEEDS_REVIEW 表。

| Group | 构件数 | License | Source |
|---|---|---|---|
| `androidx.*`（activity / annotation / arch.core / autofill / collection / compose* / concurrent / core / customview / emoji2 / graphics / interpolator / lifecycle / navigation / navigationevent / profileinstaller / room / savedstate / sqlite / startup / tracing / versionedparcelable / window） | 110 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| `org.jetbrains.kotlin*` | 2 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| `org.jetbrains.kotlinx*`（coroutines / serialization） | 9 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains:annotations` | 1 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| `org.jspecify:jspecify` | 1 | Apache-2.0 | https://github.com/jspecify/jspecify |
| `com.google.guava:listenablefuture` | 1 | NEEDS_REVIEW（见文末） | https://github.com/google/guava |

### 构建期依赖（只参与编译 / 代码生成，不进 APK）

| Component | Version | License | Source |
|---|---|---|---|
| Android Gradle Plugin（`com.android.application`） | 9.4.1 | Apache-2.0 | https://developer.android.com/build |
| Kotlin Gradle Plugin / compose 编译器插件（`org.jetbrains.kotlin.*`） | 2.3.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| KSP Gradle 插件（`com.google.devtools.ksp`） | 2.3.7 | Apache-2.0 | https://github.com/google/ksp |
| `androidx.room:room-compiler`（ksp 代码生成） | 2.8.5 | Apache-2.0 | https://developer.android.com/training/data-storage/room |
| Gradle Wrapper（`gradle/wrapper/gradle-wrapper.jar`） | 9.6.0 | Apache-2.0（读自 jar 内 `META-INF/LICENSE`） | https://github.com/gradle/gradle |

### 仅测试期依赖（`testImplementation`，不进 APK）

| Component | Version | License | Source |
|---|---|---|---|
| `junit:junit` | 4.13.2 | EPL-1.0（POM：Eclipse Public License 1.0） | https://github.com/junit-team/junit4 |
| `org.hamcrest:hamcrest-core` | 1.3 | BSD（读自 jar 内 `LICENSE.txt`：`BSD License`，Copyright (c) 2000-2006 www.hamcrest.org） | https://github.com/hamcrest/Hamcrest |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |

### 仓库内的自有内容

| Item | License | 说明 |
|---|---|---|
| `app/src/main/**`（全部 `package com.parcelhub`） | MPL-2.0 | 本项目原创源码，仓库内不含任何 vendored 第三方源码文件 |
| `app/src/main/assets/rules.json` | MPL-2.0 | 本项目自编解析规则，非第三方数据 |
| `tools/*`（冒烟测试脚本） | MPL-2.0 | 本项目原创 |

### 未确认许可证（NEEDS_REVIEW）

| Component | Reason | Action Required |
|---|---|---|
| `com.google.guava:listenablefuture:1.0` | 缓存中该构件的 POM 无 `<licenses>` 节点，jar 内也无 `LICENSE` / `NOTICE` 文件（只有 `META-INF/maven/.../pom.xml`，内容与 POM 一致）；按“禁止猜测”原则不填写许可证 | 上游确认后再补填（候选来源：https://github.com/google/guava 、Maven Central 页面）；确认前**不得**把它标为 Apache-2.0 或 MPL-2.0 |
