package com.onix.profile.config

data class AppConfig(
    val httpHost: String,
    val httpPort: Int,
    val accountBaseUrl: String,
    val accountFrontendUrl: String,
    val profilePublicUrl: String,
    val allowedOrigins: List<String>
) {
    val loginUrl: String
        get() = "${accountFrontendUrl.trimEnd('/')}/?redirect="

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String, default: String? = null): String =
                env[name]?.takeIf(String::isNotBlank) ?: default ?: error("$name is required")

            return AppConfig(
                httpHost = value("PROFILE_HTTP_HOST", "0.0.0.0"),
                httpPort = value("PROFILE_HTTP_PORT", "8080").toInt(),
                accountBaseUrl = value("PROFILE_ACCOUNT_API_URL", "http://localhost:8080/api").trimEnd('/'),
                accountFrontendUrl = value("PROFILE_ACCOUNT_FRONTEND_URL", "http://localhost:5174").trimEnd('/'),
                profilePublicUrl = value("PROFILE_PUBLIC_URL", "http://localhost:5175").trimEnd('/'),
                allowedOrigins = value("PROFILE_ALLOWED_ORIGINS", "http://localhost:5175,http://127.0.0.1:5175")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
            )
        }
    }
}
