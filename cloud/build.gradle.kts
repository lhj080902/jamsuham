import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 잠수크래프트 클라우드 서버: 앱과 같은 엔진(core)으로 서버 컴퓨터에서 24시간 잠수하고, 웹·앱에서 조작한다.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
    // 웹 서버는 자바에 들어 있는 com.sun.net.httpserver 를 쓴다 (추가 라이브러리 없음 → 서버 컴퓨터에는 자바 17 만 있으면 됨)
    implementation(project(":core"))
}

application {
    mainClass.set("kr.jamsuham.cloud.MainKt")
    applicationName = "jamsu-cloud"
}

// 앱과 같은 버전 번호를 서버에도 넣는다 (gradle.properties 의 jamsuham.version)
val appVersion: String = run {
    val f = sequenceOf(rootProject.file("gradle.properties"), rootProject.file("../../gradle.properties")).first { it.exists() && it.readText().contains("jamsuham.version=") }
    f.readLines().first { it.startsWith("jamsuham.version=") }.substringAfter('=').trim()
}
// 프로젝트 version 은 일부러 두지 않는다 → 압축 파일이 jamsu-cloud.zip, 안의 폴더가 jamsu-cloud/ 로 고정돼 업데이트할 때 덮어쓰기 쉽다

val versionFile = layout.buildDirectory.dir("generated/version")
val writeVersion = tasks.register("writeVersion") {
    val out = versionFile
    val v = appVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val f = out.get().file("jamsu-cloud-version.txt").asFile
        f.parentFile.mkdirs()
        f.writeText(v)
    }
}
sourceSets.main { resources.srcDir(writeVersion) }
