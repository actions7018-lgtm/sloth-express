import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.parcelhub"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.parcelhub"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "0.1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名：密钥与口令只存本地 keystore.properties（已 gitignore），不入库
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// APK 输出命名：ParcelHub-<versionName>-<buildType>.apk（如 ParcelHub-0.1.0-release.apk）
// AGP 9 移除了 defaultConfig.archivesName（gradle-api 9.4.1 已无该符号），
// 改在 assemble 收尾时就地重命名，并同步 output-metadata.json 里的文件名。
tasks.configureEach {
    if (name == "assembleDebug" || name == "assembleRelease") {
        doLast {
            val buildType = name.removePrefix("assemble").lowercase()
            val dir = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
            val metaFile = dir.resolve("output-metadata.json")
            val meta = if (metaFile.exists()) metaFile.readText() else null
            val version = meta?.let {
                Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            } ?: "0.1.0"
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension == "apk" && !it.name.startsWith("ParcelHub-") }
                .forEach { apk ->
                    val target = dir.resolve("ParcelHub-$version-$buildType.apk")
                    if (apk.renameTo(target)) {
                        if (meta != null) metaFile.writeText(meta.replace(apk.name, target.name))
                        logger.lifecycle("APK renamed -> ${target.name}")
                    }
                }
        }
    }
}

dependencies {
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- AndroidX 基础 ----
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // ---- 数据层：Room ----
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // ---- 规则解析（rules.json → RuleSet，纯 JVM 可单测） ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // ---- OCR 兜底（SOP V2.0 §19）：端侧中文单号识别，bundled 模型不依赖 Google Play ----
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ---- 协程（解析队列单消费者模型） ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- 单元测试：解析引擎 / 合并 / 状态机 ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
