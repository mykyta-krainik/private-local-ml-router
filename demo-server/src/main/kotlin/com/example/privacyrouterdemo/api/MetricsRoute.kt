package com.example.privacyrouterdemo.api

import com.example.privacyrouterdemo.DemoPipeline
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.metricsRoute(pipeline: DemoPipeline) {
    get("/api/metrics") {
        call.respond(HttpStatusCode.OK, pipeline.aggregateMetrics())
    }
}
