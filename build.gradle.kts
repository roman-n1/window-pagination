plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
allprojects {
    group = "io.github.roman-n1"
    version = "0.1.0-SNAPSHOT"
}

// Build-only policy; it is never packaged in the runtime artifacts.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        if (providers.gradleProperty("checkNullTypes").isPresent) {
            val policy = configurations.create("nullSafetyCompilerPlugin") {
                isTransitive = false
            }
            dependencies.add(policy.name, project(":null-safety"))
            val pluginArgument = provider { "-Xplugin=${policy.singleFile.absolutePath}" }
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
                inputs.files(policy)
                compilerOptions.freeCompilerArgs.add(pluginArgument)
            }
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
                inputs.files(policy)
                compilerOptions.freeCompilerArgs.add(pluginArgument)
            }
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
                inputs.files(policy)
                compilerOptions.freeCompilerArgs.add(pluginArgument)
            }
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon>().configureEach {
                inputs.files(policy)
                compilerOptions.freeCompilerArgs.add(pluginArgument)
            }
        }
    }
}
