package com.onix.profile

import com.onix.profile.api.registerRoutes
import com.onix.profile.account.AccountClient
import com.onix.profile.config.AppConfig
import com.onix.profile.content.ContentClient
import com.onix.profile.grpc.ProfileUsageGrpcService
import com.onix.profile.infra.Database
import com.onix.profile.infra.JdbcProfileRepository
import com.onix.profile.service.CollectionService
import com.onix.profile.service.InMemoryProfileRepository
import com.onix.profile.service.ProfileNavigationService
import com.onix.profile.service.ProfileRepository
import io.grpc.netty.NettyServerBuilder
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val config = AppConfig.fromEnv()
    val runtime = profileRuntime(config)
    val grpcServer = NettyServerBuilder.forPort(config.grpcPort)
        .addService(ProfileUsageGrpcService(runtime.navigation))
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread {
        grpcServer.shutdown()
        runtime.content.close()
        runtime.account.close()
    })
    embeddedServer(Netty, port = config.httpPort, host = config.httpHost) {
        module(config, runtime)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv(), runtime: ProfileRuntime = profileRuntime(config)) {
    registerRoutes(config, runtime)
}

data class ProfileRuntime(
    val account: AccountClient,
    val content: ContentClient,
    val repository: ProfileRepository,
    val collections: CollectionService,
    val navigation: ProfileNavigationService
)

fun profileRuntime(config: AppConfig): ProfileRuntime {
    val dataSource = Database.dataSource(config)
    val repository: ProfileRepository = if (dataSource == null) {
        System.err.println("PROFILE_DATABASE_JDBC_URL is not set; profile-service is using in-memory collection storage")
        InMemoryProfileRepository()
    } else {
        JdbcProfileRepository(dataSource)
    }
    val content = ContentClient(config, repository)
    return ProfileRuntime(
        account = AccountClient(config),
        content = content,
        repository = repository,
        collections = CollectionService(repository, content),
        navigation = ProfileNavigationService(repository, config.env)
    )
}
