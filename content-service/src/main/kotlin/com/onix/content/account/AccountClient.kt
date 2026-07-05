package com.onix.content.account

import com.onix.content.domain.AccountVisibility
import com.onix.content.domain.AccountUser
import com.onix.content.domain.SessionUser
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class AccountClient(private val apiBaseUrl: String) {
    private val http = HttpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun getMe(accessToken: String): SessionUser {
        val response = request("GET", "/users/me", accessToken)
        if (response.statusCode() == 401) throw AccountUnauthorized()
        if (response.statusCode() !in 200..299) throw AccountUnavailable("Account /users/me returned ${response.statusCode()}")
        return json.decodeFromString(response.body())
    }

    fun getUser(userId: String, accessToken: String): AccountUser? {
        val encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8)
        val response = request("GET", "/users/$encoded", accessToken)
        if (response.statusCode() == 401) throw AccountUnauthorized()
        if (response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) throw AccountUnavailable("Account /users/{id} returned ${response.statusCode()}")
        return json.decodeFromString(response.body())
    }

    fun visibility(ownerId: String, viewerId: String?, accessToken: String): AccountVisibility {
        val owner = URLEncoder.encode(ownerId, StandardCharsets.UTF_8)
        val viewer = URLEncoder.encode(viewerId.orEmpty(), StandardCharsets.UTF_8)
        val response = request("GET", "/internal/visibility?ownerId=$owner&viewerId=$viewer", accessToken)
        if (response.statusCode() in 200..299) {
            return json.decodeFromString(response.body())
        }

        // Backward-compatible fallback until the Account internal endpoint lands.
        val sameUser = ownerId == viewerId
        return AccountVisibility(ownerId = ownerId, viewerId = viewerId, isPrivate = false).copy(
            relationship = com.onix.content.domain.AccountRelationship(isFollowing = sameUser)
        )
    }

    private fun request(method: String, path: String, accessToken: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("$apiBaseUrl$path"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
        val request = when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody())
            else -> builder.GET()
        }.build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

class AccountUnauthorized : RuntimeException()
class AccountUnavailable(message: String) : RuntimeException(message)
