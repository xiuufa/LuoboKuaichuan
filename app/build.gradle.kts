plugins {
    alias(libs.plugins.android.application)
}

android {
    // 包名沿用 lobo：与旧 APP 一致，方便覆盖安装/迁移
    namespace = "com.lobo.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lobo.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            // 单元测试里 android.util.Log 等桩方法返回默认值，不抛异常
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ===== 内嵌局域网 HTTP 服务（跨端通用网页 API）=====
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ===== 二维码：生成（APP 显示 + 网页 /api/qr.svg）与扫码解码 =====
    implementation("com.google.zxing:core:3.5.3")

    // ===== 扫码：CameraX 预览 + 分析 =====
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // ===== 协程 =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ===== AndroidX / Material UI 组件 =====
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // ===== 单元测试：Range 解析、JSON、传输板存储、HTTP API 集成、上传队列 =====
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
