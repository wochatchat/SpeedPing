import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AndroidX / AGP 必须的兼容版本（Gradle 8.x runtime）
android {
    namespace = "com.wochatchat.speedping"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wochatchat.speedping"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // 使用仓库内置固定 keystore（PKCS12 格式）签名 debug + release，保持两端签名一致。
        // keystore/ 下的文件随仓库提交，不依赖 runner 上任何本机密钥。
        create("release") {
            val ksFile = rootProject.file("keystore/speedping.p12")
            val propFile = rootProject.file("keystore/keystore.properties")
            if (ksFile.exists() && propFile.exists()) {
                val props = Properties().apply { load(FileInputStream(propFile)) }
                storeFile = ksFile
                storeType = props.getProperty("storeType", "PKCS12")
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // debug 使用相同 keystore 也可，方便发行也演练一致签名
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
