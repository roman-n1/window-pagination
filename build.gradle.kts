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

// Discover compilation tasks in every KMP module; new modules cannot escape CI by omission.
val policyJvmAndAndroid = tasks.register("checkNullTypesJvmAndAndroid") {
    group = "verification"
    doFirst { check(providers.gradleProperty("checkNullTypes").isPresent) { "Use -PcheckNullTypes" } }
}
val policyWeb = tasks.register("checkNullTypesWeb") {
    group = "verification"
    doFirst { check(providers.gradleProperty("checkNullTypes").isPresent) { "Use -PcheckNullTypes" } }
}
val policyNative = tasks.register("checkNullTypesNative") {
    group = "verification"
    doFirst { check(providers.gradleProperty("checkNullTypes").isPresent) { "Use -PcheckNullTypes" } }
}

// Build-only policy; it is never packaged in the runtime artifacts.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val jvmAndAndroidTasks = tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()
        // Binary link tasks inherit the compile type but have no new source declarations.
        // Selecting both development and production links creates overlapping output graphs.
        val webTasks = tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().matching {
            it !is org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink
        }
        val nativeTasks = tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>()
        val metadataTasks = tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon>()
        policyJvmAndAndroid.configure { dependsOn(jvmAndAndroidTasks) }
        policyWeb.configure { dependsOn(webTasks) }
        policyNative.configure { dependsOn(nativeTasks, metadataTasks) }
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
            webTasks.configureEach {
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
