plugins {
    id("com.android.application")
}

android {
    namespace = "dev.kugou.kugouhook"
    // 酷狗 20.6.4 的 targetSdk 为 35，与之对齐以便反射框架类时不受编译期约束。
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kugou.kugouhook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // DexKit 的 native shim 必须在宿主进程内加载；酷狗 base.apk 的 90 个 .so 全在
        // arm64-v8a 下，32 位设备装不上宿主，因此只需要这一个 ABI。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

}


