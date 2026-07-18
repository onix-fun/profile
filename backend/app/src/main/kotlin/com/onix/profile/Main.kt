package com.onix.profile

import com.onix.profile.api.registerRoutes
import com.onix.profile.account.AccountClient
import com.onix.profile.config.AppConfig
import com.onix.profile.config.ProviderFileRepository
import com.onix.profile.content.ContentClient
import com.onix.profile.infra.Database
import com.onix.profile.infra.JdbcProfileRepository
import com.onix.profile.search.ProfileSearchOutboxWorker
import com.onix.profile.grpc.ProfileGrpcService
import com.onix.profile.grpc.ProfileInternalAuthInterceptor
import io.grpc.ServerInterceptors
import com.onix.profile.service.CollectionService
import com.onix.profile.service.ProfileNavigationService
import com.onix.profile.service.ProfileRepository
import io.grpc.netty.NettyServerBuilder
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val config = AppConfig.fromEnv()
    val runtime = profileRuntime(config)
    val grpcServer = NettyServerBuilder.forPort(config.grpcPort)
        .addService(ServerInterceptors.intercept(ProfileGrpcService(runtime.repository, runtime.collections), ProfileInternalAuthInterceptor(config.internalAuthSecret)))
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread {
        grpcServer.shutdown()
        runtime.close()
    })
    embeddedServer(Netty, port = config.httpPort, host = config.httpHost) {
        module(config, runtime)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv(), runtime: ProfileRuntime = profileRuntime(config)) {
    environment.monitor.subscribe(ApplicationStopping) { runtime.close() }
    registerRoutes(config, runtime.account, runtime.content, runtime.collections, runtime.navigation)
}

data class ProfileRuntime(
    val account: AccountClient,
    val content: ContentClient,
    val repository: ProfileRepository,
    val collections: CollectionService,
    val navigation: ProfileNavigationService,
    private val outboxWorker: ProfileSearchOutboxWorker,
    private val dataSource: AutoCloseable
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        outboxWorker.close()
        content.close()
        account.close()
        dataSource.close()
    }
}

fun profileRuntime(config: AppConfig): ProfileRuntime {
    val dataSource = requireNotNull(Database.dataSource(config)) {
        "PROFILE_DATABASE_JDBC_URL, PROFILE_DATABASE_USERNAME and PROFILE_DATABASE_PASSWORD are required"
    }
    val repository: ProfileRepository = ProviderFileRepository.load(JdbcProfileRepository(dataSource), config.providersFile)
    val content = ContentClient(config, repository)
    return ProfileRuntime(
        account = AccountClient(config),
        content = content,
        repository = repository,
        collections = CollectionService(repository, content),
        navigation = ProfileNavigationService(repository, config.env),
        outboxWorker = ProfileSearchOutboxWorker(dataSource, config.searchGrpcTarget, config.searchApiKey).start(),
        dataSource = dataSource as AutoCloseable
    )
}
