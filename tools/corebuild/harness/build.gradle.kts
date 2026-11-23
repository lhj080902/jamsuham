plugins { id("org.jetbrains.kotlin.jvm"); application }
dependencies { implementation(project(":core")) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("HarnessKt") }
