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
        versionCode = 2
        versionName = "0.1.1"

        // 宿主 base.apk 的 .so 全在 arm64-v8a 下，32 位设备根本装不上宿主，
        // 因此只需要打包这一个 ABI —— 多打只会白白增大体积。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // CI 固定签名：由 workflow 注入 keystore，保证每次构建签名一致。
        // 未配置 KEYSTORE_PATH 时（本地开发）不启用，release 回退 debug 签名。
        //
        // 为什么签名必须固定：LSPosed 装的是"模块 APK"，设备上更新模块时框架会校验
        // 新旧包签名一致。如果每次 CI 都现生成 debug keystore，用户升级时会因为
        // 签名变化而必须先卸载旧模块 —— 表现为"更新后模块失效"。
        create("ci") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: "android"
            }
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
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null)
                signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
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

    lint {
        // 关掉 release 的 lint 门禁，理由是环境限制而不是嫌它烦：
        // 本机构建必须 --offline（lint 的 jar 不在缓存里，跑不了 lintVitalAnalyzeRelease），
        // 也就是说提交前**无法复现** lint 的判定。把 CI 建在一个本地验证不了的环节上，
        // 只会让 CI 因为与本模块无关的 lint 规则变红，而我这边没法预先发现。
        //
        // 这里真正的门禁是：编译、R8 保留规则、module.prop 与 java_init.list 的一致性。
        // 要恢复 lint 就删掉下面一行，并先确认本地能联网跑通 assembleRelease。
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

}


