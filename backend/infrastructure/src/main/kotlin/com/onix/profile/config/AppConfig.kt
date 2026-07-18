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
    val searchGrpcTarget: String,
    val searchApiKey: String,
    val accountFrontendUrl: String,
    val contentGrpcUrl: String?,
    val contentGrpcUrls: List<String>,
    val profilePublicUrl: String,
    val allowedOrigins: List<String>,
    val trustedRedirectOrigins: List<String>,
    val providersFile: String,
    val internalAuthSecret: String,
    val env: Map<String, String>
) {
    val loginUrl: String
        get() = "${accountFrontendUrl.trimEnd('/')}/?redirect="

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun optional(name: String): String? {
                val direct = env[name]?.takeIf(String::isNotBlank)
                val file = env["${name}_FILE"]?.takeIf(String::isNotBlank)
                require(direct == null || file == null) { "$name and ${name}_FILE cannot both be set" }
                return file?.let { java.nio.file.Files.readString(java.nio.file.Path.of(it)).trim() } ?: direct
            }
            fun value(name: String, default: String? = null): String = optional(name) ?: default ?: error("$name is required")

            return AppConfig(
                httpHost = value("PROFILE_HTTP_HOST", "0.0.0.0"),
                httpPort = value("PROFILE_HTTP_PORT", "8080").toInt(),
                grpcPort = value("PROFILE_GRPC_PORT", "9092").toInt(),
                databaseJdbcUrl = optional("PROFILE_DATABASE_JDBC_URL"),
                databaseUsername = optional("PROFILE_DATABASE_USERNAME"),
                databasePassword = optional("PROFILE_DATABASE_PASSWORD"),
                accountGrpcUrl = value("PROFILE_ACCOUNT_GRPC_URL", "localhost:9097"),
                accountGrpcTls = value("PROFILE_ACCOUNT_GRPC_TLS", "false").toBoolean(),
                accountGrpcTrustCert = optional("PROFILE_ACCOUNT_GRPC_TRUST_CERT"),
                accountGrpcClientCert = optional("PROFILE_ACCOUNT_GRPC_CLIENT_CERT"),
                accountGrpcClientKey = optional("PROFILE_ACCOUNT_GRPC_CLIENT_KEY"),
                searchGrpcTarget = value("PROFILE_SEARCH_GRPC_TARGET"),
                searchApiKey = value("PROFILE_SEARCH_API_KEY"),
                accountFrontendUrl = value("PROFILE_ACCOUNT_FRONTEND_URL", "http://localhost:5174").trimEnd('/'),
                contentGrpcUrl = optional("PROFILE_CONTENT_GRPC_URL"),
                contentGrpcUrls = (optional("PROFILE_CONTENT_GRPC_URLS") ?: optional("PROFILE_CONTENT_GRPC_URL").orEmpty())
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
                providersFile = value("PROFILE_PROVIDERS_FILE"),
                internalAuthSecret = value("PROFILE_INTERNAL_AUTH_SECRET"),
                env = env
            )
        }
    }
}
