plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonlyrics.relay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonlyrics.relay"
        minSdk = 26
        targetSdk = 35
        // ⚠️ 跟 Mac 端(`LYRIMUSE_VERSION`,写进 CFBundleShortVersionString)保持同一个号:
        // 两端是**同一个产品**,版本号分叉之后用户报问题时就分不清在说哪一对组合。
        // 0.1.4 是 2026-09-17 补按钮反馈、链路仪表盘与解除配对的那一版。
        // versionCode 是 Android 自己的单调递增整数,与展示版本无关,改展示版本不必动它。
        versionCode = 4
        versionName = "0.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 协议夹具是 Swift 与 Kotlin **共用**的同一份,住在仓库根的 packages/protocol/fixtures/
    // (计划 Task 7 Step 1 明确要求"不要在 Android 工程里复制一份"—— 复制出来的那份迟早只改
    // 一边,而两端对同一份 JSON 的理解一旦分叉,表现是线上解码失败,不是测试失败)。
    //
    // 挂成测试资源:JVM 单元测试按类路径里的 `protocol-fixtures/xxx.json` 读,位置在这里确定,
    // 测试代码就不用靠相对路径去猜工作目录(那样在 IDE 里跑和命令行跑会得到不同结果)。
    sourceSets {
        getByName("test") {
            // ⚠️ 上溯**三级**才是仓库根:rootDir 是 PhoneLyricsRelay → android → apps → 仓库根。
            // 少一级会指到 apps/packages/...(不存在),而 srcDir 指向不存在的目录是**静默**的:
            // 构建照常成功、测试跑起来才报"夹具读不到"。
            resources.srcDir("$rootDir/../../../packages/protocol/fixtures")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
