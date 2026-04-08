package com.xremail.backend

import com.xremail.backend.config.AppConfig
import com.xremail.backend.db.DatabaseFactory
import com.xremail.backend.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = AppConfig.load(environment.config)

    // Initialize database (H2 for token/session storage)
    DatabaseFactory.init(config.database)

    // Install Ktor plugins in order
    configureSecurity(config)
    configureHTTP()
    configureSerialization()
    configureMonitoring()
    configureRouting(config)
}
