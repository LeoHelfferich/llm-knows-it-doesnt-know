package io.github.leohelfferich.abstain.analysis.model

import java.nio.file.Files
import java.nio.file.Path

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.utils.PrettyJson

/** `data/disputed.json`. Absence of the file means nothing is disputed. */
@Serializable
data class DisputedFile(val items: List<DisputedItem> = emptyList()) {
    companion object {
        fun load(path: Path): DisputedFile =
            if (Files.exists(path)) PrettyJson.decodeFromString(serializer(), Files.readString(path)) else DisputedFile()
    }
}
