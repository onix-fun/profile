package com.onix.profile.security

import io.ktor.http.*
import io.ktor.server.application.*

fun ApplicationCall.accessToken(): String? {
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(" ")
        ?.takeIf(String::isNotBlank)
    return bearer
        ?: request.cookies["__Host-access_token"]
        ?: request.cookies["access_token"]
}
