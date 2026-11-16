plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin {
    androidTarget()
    jvm()
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()
    sourceSets {
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies { implementation("org.bouncycastle:bcprov-jdk18on:1.80") }
        }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("io.ktor:ktor-client-core:3.1.1")
            implementation("io.ktor:ktor-client-websockets:3.1.1")
            listOf("io.ktor:ktor-server-cio:3.1.1", "io.ktor:ktor-server-websockets:3.1.1").forEach {
                implementation(it) {
                    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
                    exclude(group = "org.fusesource.jansi", module = "jansi")
                }
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.1.1") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.1.1") }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
}
android {
    namespace = "com.sendspindroid.shared"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
