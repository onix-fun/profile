package com.onix.content.config

data class AppConfig(
    val httpHost: String,
    val httpPort: Int,
    val grpcPort: Int,
    val accountGrpcUrl: String,
    val accountGrpcTls: Boolean,
    val accountGrpcTrustCert: String?,
    val accountGrpcClientCert: String?,
    val accountGrpcClientKey: String?,
    val mediaGrpcUrl: String?,
    val mediaGrpcTls: Boolean,
    val mediaGrpcTrustCert: String?,
    val mediaGrpcClientCert: String?,
    val mediaGrpcClientKey: String?,
    val searchGrpcUrl: String?,
    val searchGrpcTls: Boolean,
    val searchGrpcTrustCert: String?,
    val searchGrpcClientCert: String?,
    val searchGrpcClientKey: String?,
    val allowedOrigins: List<String>,
    val databaseJdbcUrl: String?,
    val databaseUsername: String,
    val databasePassword: String,
    val clickhouseUrl: String?,
    val mediaApiKey: String,
    val searchApiKey: String,
    val internalAuthSecret: String,
    val profileUsageGrpcUrl: String?
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String, default: String? = null): String =
                env[name]?.takeIf(String::isNotBlank) ?: default ?: error("$name is required")
            fun optional(name: String): String? = env[name]?.takeIf(String::isNotBlank)

            return AppConfig(
                httpHost = value("CONTENT_HTTP_HOST", "0.0.0.0"),
                httpPort = value("CONTENT_HTTP_PORT", "8080").toInt(),
                grpcPort = value("CONTENT_GRPC_PORT", "9091").toInt(),
                accountGrpcUrl = value("CONTENT_ACCOUNT_GRPC_URL", "localhost:9097"),
                accountGrpcTls = value("CONTENT_ACCOUNT_GRPC_TLS", "false").toBoolean(),
                accountGrpcTrustCert = optional("CONTENT_ACCOUNT_GRPC_TRUST_CERT"),
                accountGrpcClientCert = optional("CONTENT_ACCOUNT_GRPC_CLIENT_CERT"),
                accountGrpcClientKey = optional("CONTENT_ACCOUNT_GRPC_CLIENT_KEY"),
                mediaGrpcUrl = optional("CONTENT_MEDIA_GRPC_URL"),
                mediaGrpcTls = value("CONTENT_MEDIA_GRPC_TLS", "false").toBoolean(),
                mediaGrpcTrustCert = optional("CONTENT_MEDIA_GRPC_TRUST_CERT"),
                mediaGrpcClientCert = optional("CONTENT_MEDIA_GRPC_CLIENT_CERT"),
                mediaGrpcClientKey = optional("CONTENT_MEDIA_GRPC_CLIENT_KEY"),
                searchGrpcUrl = optional("CONTENT_SEARCH_GRPC_URL"),
                searchGrpcTls = value("CONTENT_SEARCH_GRPC_TLS", "false").toBoolean(),
                searchGrpcTrustCert = optional("CONTENT_SEARCH_GRPC_TRUST_CERT"),
                searchGrpcClientCert = optional("CONTENT_SEARCH_GRPC_CLIENT_CERT"),
                searchGrpcClientKey = optional("CONTENT_SEARCH_GRPC_CLIENT_KEY"),
                allowedOrigins = value("CONTENT_ALLOWED_ORIGINS", "http://localhost:5176,http://127.0.0.1:5176,http://content.onix.localhost:8088,http://profile.onix.localhost:8088")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank),
                databaseJdbcUrl = optional("CONTENT_DATABASE_JDBC_URL"),
                databaseUsername = value("CONTENT_DATABASE_USERNAME", "content"),
                databasePassword = value("CONTENT_DATABASE_PASSWORD", "content"),
                clickhouseUrl = optional("CONTENT_CLICKHOUSE_URL"),
                mediaApiKey = value("CONTENT_MEDIA_API_KEY", "development-media-secret"),
                searchApiKey = value("CONTENT_SEARCH_API_KEY", "development-search-secret"),
                internalAuthSecret = value("CONTENT_INTERNAL_AUTH_SECRET", "development-content-secret"),
                profileUsageGrpcUrl = optional("CONTENT_PROFILE_USAGE_GRPC_URL")
            )
        }
    }
}
