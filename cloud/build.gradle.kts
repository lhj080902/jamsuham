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
    // 메모리가 바닥나면 이상한 상태로 버티지 말고 꺼지게 한다 (자동 시작 설정이 다시 켠다). 파일은 UTF-8 로
    applicationDefaultJvmArgs = listOf("-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8")
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

// 압축 파일에 같이 넣는 안내문 (원본: cloud/guide/, docs/CLOUD.md).
//   README.txt      처음 설치하는 사람이 차례로 따라 하는 안내
//   docs/CLOUD.md   전체 설명서 (cloud.properties 의 설명 줄이 가리키는 그 경로)
//   ASK-CLAUDE.txt  막혔을 때 통째로 클로드(claude.ai)에 붙여 넣는 질문지 = 머리말 + 위 두 문서 전문 (한 번만 붙여 넣으면 되게)
// 옛 윈도우 메모장에서도 줄이 붙거나 글자가 깨지지 않게 CRLF + UTF-8 BOM 으로 바꿔 넣는다
val guideOut = layout.buildDirectory.dir("generated/guide")
val writeGuide = tasks.register("writeGuide") {
    val readme = file("guide/README.txt")
    val head = file("guide/ASK-CLAUDE.txt")
    val manual = file("../docs/CLOUD.md")
    val out = guideOut
    val v = appVersion
    inputs.files(readme, head, manual)
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val bom = "﻿"
        fun text(f: File) = f.readText(Charsets.UTF_8).removePrefix(bom).replace("\r\n", "\n").replace("@VERSION@", v).trimEnd() + "\n"
        val dir = out.get().asFile
        dir.deleteRecursively()
        fun write(name: String, body: String) = File(dir, name).apply { parentFile.mkdirs() }.writeText(bom + body.replace("\n", "\r\n"), Charsets.UTF_8)
        val r = text(readme)
        val m = text(manual)
        write("README.txt", r)
        write("docs/CLOUD.md", m)
        write("ASK-CLAUDE.txt", text(head) + "\n\n===== README.txt (설치 안내) =====\n\n" + r + "\n\n===== docs/CLOUD.md (전체 설명서) =====\n\n" + m)
    }
}
distributions { main { contents { from(writeGuide) } } }
