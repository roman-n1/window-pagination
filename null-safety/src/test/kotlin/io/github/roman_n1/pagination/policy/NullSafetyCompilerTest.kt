package io.github.roman_n1.pagination.policy

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

class NullSafetyCompilerTest {
    @Test
    fun rejectsInferredNullableAndNestedTypes() {
        val cases = listOf(
            "val item = mapOf(1 to 2)[0]",
            "fun item() = listOf(1).find { it == 2 }",
            "fun use() { val item = mapOf(1 to 2)[0]; println(item) }",
            "val items: List<String?> = emptyList()",
            "fun <T> identity(value: T): T = value",
            "class Box<T>(val value: T)",
            "val item: String? get() = null",
            "val nested: Map<String, List<Int?>> = emptyMap()",
            "val items: List<*> = listOf(1)",
        )
        for (source in cases) {
            val result = compile(source)
            assertEquals(ExitCode.COMPILATION_ERROR, result.first, source + "\n" + result.second)
            assertTrue(result.second.contains("null-safety:"), result.second)
        }
    }

    @Test
    fun acceptsExplicitStatesNonNullGenericsAndGeneratedDataClassMembers() {
        val result = compile("""
            sealed interface Focus {
                data object None : Focus
                data class Item(val id: String) : Focus
            }
            fun <T : Any> identity(value: T): T = value
            val item = mapOf(1 to 2).getValue(1)
            val text: String get() = "valid"
            fun use() { val local = identity(1); println(local) }
        """.trimIndent())
        assertEquals(ExitCode.OK, result.first, result.second)
    }

    private fun compile(source: String): Pair<ExitCode, String> {
        val directory = Files.createTempDirectory("null-safety-test").toFile()
        try {
            val file = directory.resolve("Example.kt").apply { writeText(source) }
            val bytes = ByteArrayOutputStream()
            val stdlib = Unit::class.java.protectionDomain.codeSource.location.toURI().path
            val plugin = System.getProperty("policy.jar", "")
            check(plugin.isNotEmpty())
            val status = PrintStream(bytes).use { output ->
                K2JVMCompiler().exec(output,
                    "-no-stdlib", "-no-reflect", "-classpath", stdlib,
                    "-Xplugin=$plugin", "-d", directory.resolve("classes").path, file.path)
            }
            return status to bytes.toString(Charsets.UTF_8)
        } finally {
            directory.deleteRecursively()
        }
    }
}
