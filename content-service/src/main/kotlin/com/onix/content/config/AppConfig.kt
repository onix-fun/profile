package com.onix.content.config

data class AppConfig(
    val httpHost: String,
    val httpPort: Int,
    val accountGrpcUrl: String,
    val accountGrpcTls: Boolean,
    val accountGrpcTrustCert: String?,
    val accountGrpcClientCert: String?,
    val accountGrpcClientKey: String?,
    val mediaBaseUrl: String,
    val searchBaseUrl: String,
    val allowedOrigins: List<String>,
    val databaseJdbcUrl: String?,
    val databaseUsername: String,
    val databasePassword: String,
    val clickhouseUrl: String?,
    val rabbitmqUrl: String?,
    val mediaApiKey: String,
    val searchApiKey: String,
    val internalAuthSecret: String
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String, default: String? = null): String =
                env[name]?.takeIf(String::isNotBlank) ?: default ?: error("$name is required")
            fun optional(name: String): String? = env[name]?.takeIf(String::isNotBlank)

            return AppConfig(
                httpHost = value("CONTENT_HTTP_HOST", "0.0.0.0"),
                httpPort = value("CONTENT_HTTP_PORT", "8080").toInt(),
                accountGrpcUrl = value("CONTENT_ACCOUNT_GRPC_URL", "localhost:9097"),
                accountGrpcTls = value("CONTENT_ACCOUNT_GRPC_TLS", "false").toBoolean(),
                accountGrpcTrustCert = optional("CONTENT_ACCOUNT_GRPC_TRUST_CERT"),
                accountGrpcClientCert = optional("CONTENT_ACCOUNT_GRPC_CLIENT_CERT"),
                accountGrpcClientKey = optional("CONTENT_ACCOUNT_GRPC_CLIENT_KEY"),
                mediaBaseUrl = value("CONTENT_MEDIA_API_URL", "http://localhost:8082").trimEnd('/'),
                searchBaseUrl = value("CONTENT_SEARCH_API_URL", "http://localhost:8083").trimEnd('/'),
                allowedOrigins = value("CONTENT_ALLOWED_ORIGINS", "http://localhost:5175,http://127.0.0.1:5175,http://profile.localhost:8088")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank),
                databaseJdbcUrl = optional("CONTENT_DATABASE_JDBC_URL"),
                databaseUsername = value("CONTENT_DATABASE_USERNAME", "content"),
                databasePassword = value("CONTENT_DATABASE_PASSWORD", "content"),
                clickhouseUrl = optional("CONTENT_CLICKHOUSE_URL"),
                rabbitmqUrl = optional("CONTENT_RABBITMQ_URL"),
                mediaApiKey = value("CONTENT_MEDIA_API_KEY", "development-media-secret"),
                searchApiKey = value("CONTENT_SEARCH_API_KEY", "development-search-secret"),
                internalAuthSecret = value("CONTENT_INTERNAL_AUTH_SECRET", "development-content-secret")
            )
        }
    }
}
