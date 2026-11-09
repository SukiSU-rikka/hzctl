plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.hzctl"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.hzctl"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("hzctl.jks")
            storePassword = "hzctl123"
            keyAlias = "hzctl"
            keyPassword = "hzctl123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libxposed 现代 API（LSPosed 2.2.1 = API 102），只编译不打包
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
