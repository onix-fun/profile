package com.onix.profile.config

data class AppConfig(
    val httpHost: String,
    val httpPort: Int,
    val accountGrpcUrl: String,
    val accountGrpcTls: Boolean,
    val accountGrpcTrustCert: String?,
    val accountGrpcClientCert: String?,
    val accountGrpcClientKey: String?,
    val accountFrontendUrl: String,
    val contentApiUrl: String?,
    val contentGrpcUrl: String?,
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
                accountGrpcUrl = value("PROFILE_ACCOUNT_GRPC_URL", "localhost:9097"),
                accountGrpcTls = value("PROFILE_ACCOUNT_GRPC_TLS", "false").toBoolean(),
                accountGrpcTrustCert = env["PROFILE_ACCOUNT_GRPC_TRUST_CERT"]?.takeIf(String::isNotBlank),
                accountGrpcClientCert = env["PROFILE_ACCOUNT_GRPC_CLIENT_CERT"]?.takeIf(String::isNotBlank),
                accountGrpcClientKey = env["PROFILE_ACCOUNT_GRPC_CLIENT_KEY"]?.takeIf(String::isNotBlank),
                accountFrontendUrl = value("PROFILE_ACCOUNT_FRONTEND_URL", "http://localhost:5174").trimEnd('/'),
                contentApiUrl = env["PROFILE_CONTENT_API_URL"]?.takeIf(String::isNotBlank)?.trimEnd('/'),
                contentGrpcUrl = env["PROFILE_CONTENT_GRPC_URL"]?.takeIf(String::isNotBlank),
                profilePublicUrl = value("PROFILE_PUBLIC_URL", "http://localhost:5175").trimEnd('/'),
                allowedOrigins = value("PROFILE_ALLOWED_ORIGINS", "http://localhost:5175,http://127.0.0.1:5175")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
            )
        }
    }
}
