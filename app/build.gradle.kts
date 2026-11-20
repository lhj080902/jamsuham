import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.jamsuham.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.jamsuham.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // 고정 디버그 키: 빌드할 때마다 키가 바뀌면 새 버전을 덮어 설치할 수 없어서 저장소에 넣어 둔다
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // ViaVersion 은 리플렉션/리소스를 많이 써서 코드 축소를 끈다
            isMinifyEnabled = false
            // 개인용 빌드: 디버그 키로 서명 (스토어 배포 시 별도 키로 바꿀 것)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // ViaVersion jar 안에 서버(PC)용 코드가 섞여 있어 lint 가 오류로 보는 것을 막는다
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/**",
                "META-INF/native-image/**",
                "META-INF/maven/**",
                "LICENSE*",
                "plugin.yml",
                "velocity-plugin.json",
                "fabric.mod.json",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
