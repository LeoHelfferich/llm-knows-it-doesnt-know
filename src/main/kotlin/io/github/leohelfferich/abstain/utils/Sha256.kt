package io.github.leohelfferich.abstain.utils

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Lower-case SHA-256 hex digest of [bytes]. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".formatUs(it) }

/** Lower-case SHA-256 hex digest of [text], encoded as UTF-8. */
fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** Lower-case SHA-256 hex digest of the bytes in [path]. */
fun sha256Hex(path: Path): String = sha256Hex(Files.readAllBytes(path))
