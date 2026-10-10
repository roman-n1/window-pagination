plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}
kotlin {
    jvm()
    android {
        namespace = "io.github.roman_n1.pagination.core"
        compileSdk = 37
        minSdk = 24
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()
    js { nodejs(); browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs(); browser() }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
