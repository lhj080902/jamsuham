// 이 폴더만 따로 빌드하는 Gradle 설정 (안드로이드 플러그인을 못 받는 클라우드 환경용, CLAUDE.md "테스트 (PC)" 참고)
// 실행: 저장소 루트에서 ./gradlew -p tools/corebuild :harness:installDist  /  :appcheck:compileKotlin -Pflavor=github
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "corebuild"
include(":core")
project(":core").projectDir = file("../../core")
include(":harness")
include(":appcheck")
