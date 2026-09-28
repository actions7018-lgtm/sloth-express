// 快递聚合助手 · 根构建脚本
// 工具链：Gradle 9.6.0 + AGP 9.4.1 + Kotlin 2.3.21（AGP 内置 Kotlin）+ KSP 2.3.7
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
}
