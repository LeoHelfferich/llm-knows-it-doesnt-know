package io.github.leohelfferich.abstain.squad

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

import io.github.leohelfferich.abstain.utils.sha256Hex
import io.github.leohelfferich.abstain.squad.model.SampleSource

/** Where the SQuAD 2.0 dev set is downloaded from, and the checksum it must match. */
object SquadSource {
    const val NAME = "SQuAD 2.0 dev set (dev-v2.0.json)"
    const val URL = "https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v2.0.json"
    const val SHA256 = "80a5225e94905956a6446d296ca1093975c4d3b3260f1d6c8f68bc2ab77182d8"
    const val LICENSE = "CC BY-SA 4.0"
    val source get() = SampleSource(NAME, URL, SHA256, LICENSE)

    fun download(target: Path) {
        Files.createDirectories(target.parent)
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(
            HttpRequest.newBuilder(URI.create(URL)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "Download failed: HTTP ${response.statusCode()}" }
        Files.write(target, response.body())
    }

    fun verifyChecksum(file: Path) {
        val actual = sha256Hex(file)
        check(actual == SHA256) { "Checksum mismatch for $file: expected $SHA256 but got $actual" }
    }
}
