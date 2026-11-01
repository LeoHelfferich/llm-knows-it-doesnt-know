package io.github.leohelfferich.abstain.config

import java.nio.file.Files
import java.nio.file.Path

import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.squad.model.SampleFile
import io.github.leohelfferich.abstain.config.model.ExperimentConfig

/** All repo-relative locations in one place so tests can run against a temporary workspace. */
class Workspace(root: Path) {
    val configFile: Path = root.resolve("config/experiment.json")
    val snapshotFile: Path = root.resolve("config/openrouter-models.snapshot.json")
    val rawDir: Path = root.resolve("data/raw")
    val sampleFile: Path = root.resolve("data/sample.json")
    val disputedFile: Path = root.resolve("data/disputed.json")
    val runsDir: Path = root.resolve("data/runs")
    val mockRunsDir: Path = root.resolve("build/mock-runs")
    val docsData: Path = root.resolve("docs/data")

    fun loadConfig(): ExperimentConfig =
        PrettyJson.decodeFromString(ExperimentConfig.serializer(), Files.readString(configFile))

    fun loadSample(): SampleFile =
        PrettyJson.decodeFromString(SampleFile.serializer(), Files.readString(sampleFile))
}
