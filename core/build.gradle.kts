import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ViaVersion / ViaBackwards / ViaRewind (GPLv3) - tools/update-via.sh 로 안드로이드용으로 고친 jar
    api(fileTree("libs") { include("*.jar") })

    // ViaVersion 이 서버 플랫폼에서 받아 쓰는 라이브러리들 (Netty 는 안드로이드 지원이 오래 검증된 4.1 사용)
    api("io.netty:netty-handler:4.1.141.Final")
    api("io.netty:netty-transport:4.1.141.Final")
    api("com.google.code.gson:gson:2.14.0")
    api("com.google.guava:guava:33.7.1-android")

    // 마이크로소프트 계정 로그인
    api("net.raphimc:MinecraftAuth:5.0.2")
}
