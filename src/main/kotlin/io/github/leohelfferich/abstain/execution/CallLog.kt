package io.github.leohelfferich.abstain.execution

import java.nio.file.Files
import java.nio.file.Path

import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.execution.model.CallRecord

object CallLog {
    fun file(runDir: Path): Path = runDir.resolve("calls.jsonl")

    fun read(runDir: Path): List<CallRecord> =
        readJsonl(file(runDir)) + readJsonl(runDir.resolve("failed-attempts.jsonl"))

    private fun readJsonl(f: Path): List<CallRecord> {
        if (!Files.exists(f)) return emptyList()
        val lines = Files.readAllLines(f).filter { it.isNotBlank() }
        val out = ArrayList<CallRecord>(lines.size)
        lines.forEachIndexed { index, line ->
            try {
                out += CompactJson.decodeFromString(CallRecord.serializer(), line)
            } catch (e: Exception) {
                // A killed process can leave a partial last line. Dropping it retries that one call.
                // A bad line earlier means the log was corrupted; continuing would respend or drop data silently.
                if (index != lines.lastIndex) throw IllegalStateException("Corrupt line ${index + 1} in $f", e)
            }
        }
        return out
    }

    /** Latest ok record per key. */
    fun okByKey(records: List<CallRecord>): Map<String, CallRecord> =
        records.filter { it.status == "ok" }.associateBy { it.key }

    fun spentUsdAcrossRuns(runsDir: Path): Double {
        if (!Files.isDirectory(runsDir)) return 0.0
        Files.list(runsDir).use { dirs ->
            return dirs.filter { Files.isDirectory(it) }.toList().sumOf { d -> read(d).sumOf { it.costUsd ?: 0.0 } }
        }
    }
}
