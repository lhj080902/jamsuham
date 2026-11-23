plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
val flavor = (findProperty("flavor") as String?) ?: "github"
sourceSets { main { kotlin.srcDir(rootDir.resolve("../../app/src/main/kotlin")); kotlin.srcDir(rootDir.resolve("../../app/src/$flavor/kotlin")) } }
layout.buildDirectory.set(file("build-$flavor"))
dependencies {
    implementation(project(":core"))
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
}
