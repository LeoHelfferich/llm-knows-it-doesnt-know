package io.github.leohelfferich.abstain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoHygieneTest {
    private val skipped = setOf("build", ".gradle", ".git", ".kotlin", "raw", ".idea")

    private fun files(): List<Path> = Files.walk(repoRoot).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { p -> repoRoot.relativize(p).none { it.toString() in skipped } }
            .filter { it.fileName.toString() != "gradle-wrapper.jar" }
            .toList()
    }

    @Test
    fun `no API key shaped strings are committed anywhere`() {
        val pattern = Regex("sk-or-v1-[A-Za-z0-9]{16,}")
        val offenders = files().filter { p ->
            val name = p.fileName.toString()
            !name.endsWith("Test.kt") && pattern.containsMatchIn(Files.readString(p))
        }
        assertEquals(emptyList(), offenders.map { repoRoot.relativize(it).toString() })
    }

    @Test
    fun `env files and key files are git-ignored`() {
        val ignore = Files.readString(repoRoot.resolve(".gitignore"))
        listOf(".env", "*.key", "data/raw/").forEach { assertTrue(it in ignore, "$it missing from .gitignore") }
    }

    @Test
    fun `source never prints the key variable unless it is redacted first`() {
        val offenders = files().filter { it.toString().contains("src/main") && it.toString().endsWith(".kt") }.flatMap { p ->
            Files.readAllLines(p).filter { line ->
                Regex("(println|print|log)\\(").containsMatchIn(line) && Regex("\\$\\{?(apiKey|key)\\b|apiKeyFromEnv").containsMatchIn(line) && "redact" !in line
            }
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `license and readme are present`() {
        assertTrue(Files.readString(repoRoot.resolve("LICENSE")).startsWith("MIT License"))
        assertTrue(Files.exists(repoRoot.resolve("README.md")))
    }
}
