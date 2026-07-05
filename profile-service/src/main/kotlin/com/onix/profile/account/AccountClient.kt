package com.onix.profile.account

import com.onix.profile.domain.AccountProfile
import com.onix.profile.domain.AccountSearchUser
import com.onix.profile.domain.Relationship
import com.onix.profile.domain.SessionUser
import kotlinx.serialization.Serializable
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

    fun getProfile(username: String, accessToken: String): AccountProfile {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8)
        val response = request("GET", "/profile/$encoded", accessToken)
        return when (response.statusCode()) {
            401 -> throw AccountUnauthorized()
            403 -> throw AccountForbidden()
            404 -> throw AccountNotFound()
            in 200..299 -> json.decodeFromString(response.body())
            else -> throw AccountUnavailable("Account /profile returned ${response.statusCode()}")
        }
    }

    fun follow(userId: String, accessToken: String): Relationship {
        val response = request("POST", "/profile/$userId/follow", accessToken)
        if (response.statusCode() == 401) throw AccountUnauthorized()
        if (response.statusCode() == 403) throw AccountForbidden()
        if (response.statusCode() == 404) throw AccountNotFound()
        if (response.statusCode() !in 200..299) throw AccountUnavailable("Account follow returned ${response.statusCode()}")
        return json.decodeFromString(response.body())
    }

    fun unfollow(userId: String, accessToken: String) {
        val response = request("DELETE", "/profile/$userId/follow", accessToken)
        if (response.statusCode() == 401) throw AccountUnauthorized()
        if (response.statusCode() == 403) throw AccountForbidden()
        if (response.statusCode() == 404) throw AccountNotFound()
        if (response.statusCode() !in 200..299) throw AccountUnavailable("Account unfollow returned ${response.statusCode()}")
    }

    fun searchUsers(query: String, limit: Int, accessToken: String): List<AccountSearchUser> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val cappedLimit = limit.coerceIn(1, 50)
        val response = request("GET", "/search/search?q=$encodedQuery&limit=$cappedLimit", accessToken)
        if (response.statusCode() == 401) throw AccountUnauthorized()
        if (response.statusCode() == 403) throw AccountForbidden()
        if (response.statusCode() !in 200..299) throw AccountUnavailable("Account profile search returned ${response.statusCode()}")
        return json.decodeFromString(response.body())
    }

    private fun request(method: String, path: String, accessToken: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("$apiBaseUrl$path"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
        val request = when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody())
            "DELETE" -> builder.DELETE()
            else -> builder.GET()
        }.build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

class AccountUnauthorized : RuntimeException()
class AccountForbidden : RuntimeException()
class AccountNotFound : RuntimeException()
class AccountUnavailable(message: String) : RuntimeException(message)
