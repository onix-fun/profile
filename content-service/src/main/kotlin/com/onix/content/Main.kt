package com.onix.content

import com.onix.content.account.AccountClient
import com.onix.content.api.registerRoutes
import com.onix.content.config.AppConfig
import com.onix.content.infra.Database
import com.onix.content.infra.JdbcContentRepository
import com.onix.content.media.MediaClient
import com.onix.content.search.RabbitSearchEventPublisher
import com.onix.content.service.ContentRepository
import com.onix.content.service.ContentService
import com.onix.content.service.InMemoryContentRepository
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.httpPort, host = config.httpHost) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    val dataSource = Database.dataSource(config)
    val repository: ContentRepository = if (dataSource == null) {
        environment.log.warn("CONTENT_DATABASE_JDBC_URL is not set; content-service is using in-memory storage")
        InMemoryContentRepository()
    } else {
        JdbcContentRepository(dataSource)
    }
    val account = AccountClient(config)
    runCatching { account.registerContentNotificationCatalog() }
        .onFailure { environment.log.warn("Failed to register content notification catalog: ${it.message}") }
    registerRoutes(
        config = config,
        account = account,
        media = MediaClient(config.mediaBaseUrl, config.mediaApiKey),
        content = ContentService(repository, RabbitSearchEventPublisher(config.rabbitmqUrl))
    )
}
