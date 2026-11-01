package io.github.leohelfferich.abstain.hygiene

import io.github.leohelfferich.abstain.testing.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RepoHygieneTest {
    private val skipped = setOf("build", "out", "bin", ".gradle", ".git", ".kotlin", ".idea", ".vscode", "raw")

    @Test
    fun `should not commit an API key`() {
        val pattern = Regex("sk-or-v1-[A-Za-z0-9]{16,}")
        val trackedFiles = files().filter { path ->
            val name = path.fileName.toString()
            !name.endsWith("Test.kt")
        }

        // ---
        val offenders = trackedFiles.filter { pattern.containsMatchIn(Files.readString(it)) }
        // ---

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `should ignore local build output and secrets`() {
        val ignore = Files.readString(repoRoot.resolve(".gitignore"))
        val required = listOf(
            ".env",
            "*.key",
            "data/raw/",
            "build/",
            ".gradle/",
            ".idea/",
            ".vscode/",
            "bin/",
            "out/",
            ".kotlin/",
        )

        // ---
        val missing = required.filter { it !in ignore }
        // ---

        assertThat(missing).isEmpty()
    }

    @Test
    fun `should redact the key before printing it`() {
        val sources = files().filter { it.toString().contains("src/main") && it.toString().endsWith(".kt") }
        val prints = Regex("(println|print|log)\\(")
        val mentionsKey = Regex("\\$\\{?(apiKey|key)\\b|apiKeyFromEnv")

        // ---
        val offenders = sources.flatMap { path ->
            Files.readAllLines(path).filter { line ->
                prints.containsMatchIn(line) && mentionsKey.containsMatchIn(line) && "redact" !in line
            }
        }
        // ---

        assertThat(offenders).isEmpty()
    }


    private fun files(): List<Path> = Files.walk(repoRoot).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { path -> repoRoot.relativize(path).none { it.toString() in skipped } }
            .filter { it.fileName.toString() != "gradle-wrapper.jar" }
            .toList()
    }
}
