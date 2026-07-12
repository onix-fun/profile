package com.onix.profile.content

import com.onix.profile.domain.ProfileContentSummary
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class ContentClient(private val apiBaseUrl: String?) {
    private val http = HttpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun profileContent(ownerId: String, accessToken: String): ProfileContentSummary {
        return profileContent("USER", ownerId, accessToken)
    }

    fun profileContent(ownerType: String, ownerId: String, accessToken: String): ProfileContentSummary {
        val base = apiBaseUrl ?: return ProfileContentSummary()
        val encoded = URLEncoder.encode(ownerId, StandardCharsets.UTF_8)
        val encodedType = URLEncoder.encode(ownerType, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("$base/owners/$encodedType/$encoded/content?postLimit=500&storyLimit=8"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return ProfileContentSummary()
        return json.decodeFromString(response.body())
    }
}
