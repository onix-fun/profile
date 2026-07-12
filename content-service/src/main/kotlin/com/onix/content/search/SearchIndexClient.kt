package com.onix.content.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

interface SearchIndexClient {
    fun search(collection: String, query: String, limit: Int): SearchIndexResult

    companion object {
        fun noop(): SearchIndexClient = object : SearchIndexClient {
            override fun search(collection: String, query: String, limit: Int): SearchIndexResult =
                SearchIndexResult(error = "Search index is not configured")
        }
    }
}

data class SearchIndexHit(
    val id: String,
    val score: Double = 0.0,
    val snippet: String? = null
)

data class SearchIndexResult(
    val hits: List<SearchIndexHit> = emptyList(),
    val error: String? = null
)

class HttpSearchIndexClient(
    private val baseUrl: String,
    private val apiKey: String
) : SearchIndexClient {
    private val http = HttpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun search(collection: String, query: String, limit: Int): SearchIndexResult {
        if (query.isBlank()) return SearchIndexResult()
        val body = JsonObject(
            mapOf(
                "collection" to JsonPrimitive(collection),
                "q" to JsonPrimitive(query),
                "query" to JsonPrimitive(query),
                "limit" to JsonPrimitive(limit.coerceIn(1, 100))
            )
        )
        val response = runCatching { post("$baseUrl/search", body) }
            .recoverCatching { post("$baseUrl/collections/${encode(collection)}/search", body) }
            .recoverCatching { get("$baseUrl/search/${encode(collection)}?q=${encode(query)}&limit=${limit.coerceIn(1, 100)}") }
            .recoverCatching { get("$baseUrl/search?collection=${encode(collection)}&q=${encode(query)}&limit=${limit.coerceIn(1, 100)}") }
            .getOrElse { return SearchIndexResult(error = it.message ?: "Search index unavailable") }

        if (response.statusCode() !in 200..299) {
            return SearchIndexResult(error = "Search index returned ${response.statusCode()} for $collection")
        }

        return runCatching { parseHits(response.body()) }
            .map { SearchIndexResult(hits = it) }
            .getOrElse { SearchIndexResult(error = "Search index response could not be parsed for $collection") }
    }

    private fun post(url: String, body: JsonObject): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("X-API-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("X-API-Key", apiKey)
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun parseHits(body: String): List<SearchIndexHit> {
        val root = json.parseToJsonElement(body)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["hits"]?.jsonArray
                ?: root["items"]?.jsonArray
                ?: root["results"]?.jsonArray
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull(::parseHit)
    }

    private fun parseHit(element: JsonElement): SearchIndexHit? {
        val obj = element as? JsonObject ?: return null
        val document = (obj["document"] as? JsonObject) ?: obj
        val id = listOf("id", "document_id", "documentId", "_id")
            .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull ?: document[key]?.jsonPrimitive?.contentOrNull }
            ?: return null
        val score = listOf("score", "_rankingScore", "rankingScore")
            .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.doubleOrNull }
            ?: 0.0
        val snippet = listOf("snippet", "_formatted", "highlight")
            .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull }
        return SearchIndexHit(id = id, score = score, snippet = snippet)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
