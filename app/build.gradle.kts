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
        // 빌드 번호 = GitHub Actions 실행 번호 (계속 커져야 함: Play·앱 안 업데이트가 새 버전인지 이걸로 비교)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        // 보이는 버전 = gradle.properties 의 jamsuham.version (주.부.수, 바꿀 때만 직접 고침)
        versionName = (project.findProperty("jamsuham.version") as String?) ?: "0.0.0"
    }

    signingConfigs {
        // 고정 디버그 키: 빌드할 때마다 키가 바뀌면 새 버전을 덮어 설치할 수 없어서 저장소에 넣어 둔다 (GitHub 판 전용)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play 업로드 키: 저장소에 넣지 않고 GitHub Actions Secrets 로 받는다 (docs/RELEASE_KEY.md)
        val uploadStore = System.getenv("JAMSUHAM_UPLOAD_STORE")
        if (!uploadStore.isNullOrBlank() && file(uploadStore).exists()) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("JAMSUHAM_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("JAMSUHAM_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("JAMSUHAM_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    // github = 지금처럼 GitHub 릴리스로 배포 (앱 안 업데이트 포함) / play = Google Play 용 (앱 안 업데이트·설치 권한 없음)
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("play") { dimension = "store" }
    }

    buildTypes {
        release {
            // ViaVersion 은 리플렉션/리소스를 많이 써서 코드 축소를 끈다
            isMinifyEnabled = false
            // 업로드 키가 있으면 그걸로, 없으면 디버그 키로 (디버그 키로 서명한 AAB 는 Play 에 올릴 수 없음)
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
                "META-INF/license/**",
                "META-INF/proguard/**",
                "META-INF/sponge_plugins.json",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
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
