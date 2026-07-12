package com.onix.content

import com.onix.content.account.AccountClient
import com.onix.content.api.registerRoutes
import com.onix.content.config.AppConfig
import com.onix.content.grpc.ContentGrpcAuthInterceptor
import com.onix.content.grpc.ContentSearchGrpcService
import com.onix.content.infra.Database
import com.onix.content.infra.JdbcContentRepository
import com.onix.content.media.MediaClient
import com.onix.content.search.HttpSearchIndexClient
import com.onix.content.search.RabbitSearchEventPublisher
import com.onix.content.service.ContentRepository
import com.onix.content.service.ContentService
import com.onix.content.service.InMemoryContentRepository
import io.grpc.ServerInterceptors
import io.grpc.netty.NettyServerBuilder
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val config = AppConfig.fromEnv()
    val runtime = contentRuntime(config)
    val grpcServer = NettyServerBuilder.forPort(config.grpcPort)
        .addService(ServerInterceptors.intercept(ContentSearchGrpcService(runtime.content, runtime.account), ContentGrpcAuthInterceptor()))
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread {
        grpcServer.shutdown()
        runtime.account.close()
    })
    embeddedServer(Netty, port = config.httpPort, host = config.httpHost) {
        module(config, runtime)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv(), runtime: ContentRuntime = contentRuntime(config)) {
    registerRoutes(
        config = config,
        account = runtime.account,
        media = runtime.media,
        content = runtime.content
    )
}

data class ContentRuntime(
    val account: AccountClient,
    val media: MediaClient,
    val content: ContentService
)

fun contentRuntime(config: AppConfig): ContentRuntime {
    val dataSource = Database.dataSource(config)
    val repository: ContentRepository = if (dataSource == null) {
        System.err.println("CONTENT_DATABASE_JDBC_URL is not set; content-service is using in-memory storage")
        InMemoryContentRepository()
    } else {
        JdbcContentRepository(dataSource)
    }
    val account = AccountClient(config)
    runCatching { account.registerContentNotificationCatalog() }
        .onFailure { System.err.println("Failed to register content notification catalog: ${it.message}") }
    return ContentRuntime(
        account = account,
        media = MediaClient(config.mediaBaseUrl, config.mediaApiKey),
        content = ContentService(
            repository = repository,
            searchEvents = RabbitSearchEventPublisher(config.rabbitmqUrl),
            searchIndex = HttpSearchIndexClient(config.searchBaseUrl, config.searchApiKey)
        )
    )
}
