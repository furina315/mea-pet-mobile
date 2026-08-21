import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取友盟 AppKey 与独特性标识信息，与代码隔离
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val umengAppKey: String = localProperties.getProperty("umeng.appKey", "") ?: ""
// 友盟分发渠道名（按分发来源命名）
val umengChannel: String = localProperties.getProperty("umeng.channel", "GitHub") ?: "GitHub"
// 开发者标识 / 仓库地址 / 交流群链接 / 友盟隐私政策链接（开源分叉时按需替换）
val devName: String = localProperties.getProperty("app.devName", "") ?: ""
val gitRepoUrl: String = localProperties.getProperty("app.gitRepoUrl", "") ?: ""
val qqGroupUrl: String = localProperties.getProperty("app.qqGroupUrl", "") ?: ""
val umengPolicyUrl: String = localProperties.getProperty("app.umengPolicyUrl", "") ?: ""
// TTS 模型 / 日语词典下载地址（开源分叉时按需替换；缺省为空表示未配置，设置里下载入口将提示）
val ttsModelBaseUrl: String = localProperties.getProperty("app.ttsModelBaseUrl", "") ?: ""
val ttsJaDicUrl: String = localProperties.getProperty("app.ttsJaDicUrl", "") ?: ""

android {
    namespace = "com.meapet.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.meapet.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode =  9
        versionName = "1.6.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // 友盟 AppKey 通过 BuildConfig 注入，源码中不硬编码
        buildConfigField("String", "UMENG_APP_KEY", "\"$umengAppKey\"")
        // 友盟分发渠道名同样通过 BuildConfig 注入（分叉时可替换为自己的渠道）
        buildConfigField("String", "UMENG_CHANNEL", "\"$umengChannel\"")
        // 独特性标识信息同样通过 BuildConfig 注入，缺失时保持 _unset 占位（运行时回退默认）
        buildConfigField("String", "DEV_NAME", "\"$devName\"")
        buildConfigField("String", "GIT_REPO_URL", "\"$gitRepoUrl\"")
        buildConfigField("String", "QQ_GROUP_URL", "\"$qqGroupUrl\"")
        buildConfigField("String", "UMENG_POLICY_URL", "\"$umengPolicyUrl\"")
        buildConfigField("String", "TTS_MODEL_BASE_URL", "\"$ttsModelBaseUrl\"")
        buildConfigField("String", "TTS_JA_DIC_URL", "\"$ttsJaDicUrl\"")
    }

    buildTypes {
        release {
            // 开启 R8（代码收缩/混淆 + 资源收缩）；keep 规则见 proguard-rules.pro
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // piper-plus-g2p 与 onnxruntime-android 的 AAR 都内嵌 libonnxruntime.so（版本不一，
    // piper 的精简版缺 OrtGetApiBase）。解决办法：把 onnxruntime-android 1.23.2 的完整版
    // 放进 app/src/main/jniLibs/（项目 jniLibs 在 pickFirst 中优先级高于 AAR 依赖），
    // 并用 pickFirsts 去重，确保打包进的是含 OrtGetApiBase 的完整版。
    packaging {
        jniLibs {
            pickFirsts += "lib/**/libonnxruntime.so"
        }
    }
    // OpenJTalk 词典/拼音表等大文本 assets 需禁压缩（否则 aapt 压缩后某些读取路径会失败）
    aaptOptions {
        noCompress += listOf("bin", "dic", "txt")
    }
    testOptions {
        unitTests {
            // JVM 单测中 android.util.Log 等桩方法返回默认值而非抛异常
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // Live2D Cubism Core
    implementation(files("libs/Live2DCubismCore.aar"))

    // Ktor Client (for OpenAI client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Kotlinx Serialization (for OpenAI client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 友盟+ 统计 SDK (U-APP)
    implementation(libs.umeng.umsdk.common)  // 必选：统计核心
    implementation(libs.umeng.umsdk.asms)    // 必选：重要组件

    // 本地 VITS TTS：ONNX Runtime + 日语 G2P（OpenJTalk 前端）
    implementation(libs.onnxruntime.android)
    // piper-plus-g2p 的 AAR 内嵌了精简版 libonnxruntime.so，与 onnxruntime-android 的 1.23.2 冲突
    // （会导致 UnsatisfiedLinkError: OrtGetApiBase）。下面的 packagingOptions 把 piper 那份排除掉，
    // 统一用 onnxruntime-android 的完整版；piper 自己的 libpiper_plus*.so 保留。
    implementation(libs.piper.plus.g2p)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 测试所需
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
}