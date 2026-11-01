package io.github.leohelfferich.abstain.execution

/** Commit hash and dirty flag recorded in the manifest. Missing git yields nulls, not a failure. */
object GitInfo {
    fun read(): Pair<String?, Boolean?> = try {
        val commit = run("git", "rev-parse", "HEAD")
        val dirty = run("git", "status", "--porcelain")?.isNotBlank()
        commit to dirty
    } catch (_: Exception) {
        null to null
    }

    private fun run(vararg cmd: String): String? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0) out else null
    }
}
