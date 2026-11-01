package io.github.leohelfferich.abstain.client

/** Reads the OpenRouter key from the environment and strips it from anything that might be printed. */
object Secrets {
    const val ENV_VAR = "OPENROUTER_API_KEY"

    fun apiKeyFromEnv(env: Map<String, String> = System.getenv()): String? =
        env[ENV_VAR]?.trim()?.takeIf { it.isNotEmpty() }

    fun redact(text: String, key: String?): String {
        var out = text
        if (!key.isNullOrBlank()) out = out.replace(key, "[REDACTED]")
        return out.replace(Regex("sk-or-[A-Za-z0-9_\\-]{8,}"), "[REDACTED]")
    }
}
