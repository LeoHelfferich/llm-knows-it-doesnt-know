package io.github.leohelfferich.abstain.utils

import kotlinx.serialization.json.Json

/** Pretty JSON used for files a person will read: manifests, samples, published results. */
val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

/** Single-line JSON used for the call log, where one record is one line. */
val CompactJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}
