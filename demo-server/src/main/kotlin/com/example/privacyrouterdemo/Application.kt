package com.example.privacyrouterdemo

import com.example.privacyrouterdemo.api.processRoute
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val pipeline = DemoPipeline()

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            gson { setPrettyPrinting() }
        }
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            anyHost()
        }
        routing {
            processRoute(pipeline)
        }
    }.start(wait = true)
}
