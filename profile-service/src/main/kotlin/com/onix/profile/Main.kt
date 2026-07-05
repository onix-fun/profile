package com.onix.profile

import com.onix.profile.api.registerRoutes
import com.onix.profile.config.AppConfig
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
    registerRoutes(config)
}
