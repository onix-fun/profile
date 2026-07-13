package com.onix.profile.config

data class AppConfig(
    val httpHost: String,
    val httpPort: Int,
    val grpcPort: Int,
    val databaseJdbcUrl: String?,
    val databaseUsername: String?,
    val databasePassword: String?,
    val accountGrpcUrl: String,
    val accountGrpcTls: Boolean,
    val accountGrpcTrustCert: String?,
    val accountGrpcClientCert: String?,
    val accountGrpcClientKey: String?,
    val accountFrontendUrl: String,
    val contentGrpcUrl: String?,
    val contentGrpcUrls: List<String>,
    val profilePublicUrl: String,
    val allowedOrigins: List<String>,
    val trustedRedirectOrigins: List<String>,
    val env: Map<String, String>
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
                grpcPort = value("PROFILE_GRPC_PORT", "9092").toInt(),
                databaseJdbcUrl = env["PROFILE_DATABASE_JDBC_URL"]?.takeIf(String::isNotBlank),
                databaseUsername = env["PROFILE_DATABASE_USERNAME"]?.takeIf(String::isNotBlank),
                databasePassword = env["PROFILE_DATABASE_PASSWORD"]?.takeIf(String::isNotBlank),
                accountGrpcUrl = value("PROFILE_ACCOUNT_GRPC_URL", "localhost:9097"),
                accountGrpcTls = value("PROFILE_ACCOUNT_GRPC_TLS", "false").toBoolean(),
                accountGrpcTrustCert = env["PROFILE_ACCOUNT_GRPC_TRUST_CERT"]?.takeIf(String::isNotBlank),
                accountGrpcClientCert = env["PROFILE_ACCOUNT_GRPC_CLIENT_CERT"]?.takeIf(String::isNotBlank),
                accountGrpcClientKey = env["PROFILE_ACCOUNT_GRPC_CLIENT_KEY"]?.takeIf(String::isNotBlank),
                accountFrontendUrl = value("PROFILE_ACCOUNT_FRONTEND_URL", "http://localhost:5174").trimEnd('/'),
                contentGrpcUrl = env["PROFILE_CONTENT_GRPC_URL"]?.takeIf(String::isNotBlank),
                contentGrpcUrls = (env["PROFILE_CONTENT_GRPC_URLS"] ?: env["PROFILE_CONTENT_GRPC_URL"].orEmpty())
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank),
                profilePublicUrl = value("PROFILE_PUBLIC_URL", "http://localhost:5175").trimEnd('/'),
                allowedOrigins = value("PROFILE_ALLOWED_ORIGINS", "http://localhost:5175,http://127.0.0.1:5175")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank),
                trustedRedirectOrigins = value(
                    "PROFILE_TRUSTED_REDIRECT_ORIGINS",
                    env["PROFILE_ALLOWED_ORIGINS"] ?: "http://localhost:5175,http://127.0.0.1:5175"
                )
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank),
                env = env
            )
        }
    }
}
