package com.onix.content

import com.onix.content.account.AccountClient
import com.onix.content.api.registerRoutes
import com.onix.content.config.AppConfig
import com.onix.content.grpc.ContentGrpcAuthInterceptor
import com.onix.content.grpc.ContentProviderGrpcService
import com.onix.content.grpc.ContentSearchGrpcService
import com.onix.content.grpc.UnifiedContentProviderGrpcService
import com.onix.content.infra.Database
import com.onix.content.infra.JdbcContentRepository
import com.onix.content.media.MediaClient
import com.onix.content.profile.GrpcProfileUsageReporter
import com.onix.content.profile.ProfileUsageReporter
import com.onix.content.search.HttpSearchIndexClient
import com.onix.content.search.OutboxSearchEventPublisher
import com.onix.content.search.SearchIndexClient
import com.onix.content.search.SearchOutboxWorker
import com.onix.content.service.ContentRepository
import com.onix.content.service.ContentService
import com.onix.content.service.InMemoryContentRepository
import com.onix.content.service.PublicationReconciliationWorker
import com.onix.content.service.UploadedAssetVerifier
import com.onix.content.service.MediaAssetProcessor
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
        .addService(ServerInterceptors.intercept(ContentProviderGrpcService(runtime.content, runtime.account), ContentGrpcAuthInterceptor()))
        .addService(ServerInterceptors.intercept(UnifiedContentProviderGrpcService(runtime.content, runtime.account), ContentGrpcAuthInterceptor()))
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread {
        grpcServer.shutdown()
        runtime.account.close()
        runtime.media.close()
        runtime.profileUsage.close()
        runtime.searchOutbox?.close()
        runtime.publicationReconciler.close()
        (runtime.searchIndex as? AutoCloseable)?.close()
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
    val content: ContentService,
    val profileUsage: ProfileUsageReporter,
    val searchIndex: SearchIndexClient,
    val searchOutbox: SearchOutboxWorker?,
    val publicationReconciler: PublicationReconciliationWorker
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
    val profileUsage = GrpcProfileUsageReporter(config.profileUsageGrpcUrl)
    val searchIndex = HttpSearchIndexClient(
        target = config.searchGrpcUrl,
        apiKey = config.searchApiKey,
        tls = config.searchGrpcTls,
        trustCert = config.searchGrpcTrustCert,
        clientCert = config.searchGrpcClientCert,
        clientKey = config.searchGrpcClientKey
    )
    val searchOutbox = dataSource?.let { SearchOutboxWorker(it, searchIndex).start() }
    val media = MediaClient(
        target = config.mediaGrpcUrl,
        apiKey = config.mediaApiKey,
        tls = config.mediaGrpcTls,
        trustCert = config.mediaGrpcTrustCert,
        clientCert = config.mediaGrpcClientCert,
        clientKey = config.mediaGrpcClientKey
    )
    val content = ContentService(
        repository = repository,
        searchEvents = OutboxSearchEventPublisher(dataSource),
        searchIndex = searchIndex,
        profileUsage = profileUsage,
        uploadedAssetVerifier = object : UploadedAssetVerifier {
            override fun asset(owner: String, assetId: String) =
                runCatching { media.getAssetForOwner(owner, assetId) }.getOrNull()

            override fun assets(owner: String, assetIds: List<String>) =
                runCatching { media.getAssetsForOwner(owner, assetIds) }.getOrDefault(emptyMap())
        },
        mediaAssetProcessor = object : MediaAssetProcessor {
            override fun request(owner: String, assetId: String, kind: com.onix.content.domain.PostAssetKind, idempotencyKey: String) =
                media.requestProcessing(owner, assetId, kind, idempotencyKey)
            override fun cancel(owner: String, runId: String) = media.cancelProcessing(owner, runId)
            override fun releaseSource(owner: String, assetId: String, generation: Long) = media.releaseSource(owner, assetId, generation)
        }
    )
    return ContentRuntime(
        account = account,
        media = media,
        profileUsage = profileUsage,
        searchIndex = searchIndex,
        searchOutbox = searchOutbox,
        content = content,
        publicationReconciler = PublicationReconciliationWorker(content, media).start()
    )
}
