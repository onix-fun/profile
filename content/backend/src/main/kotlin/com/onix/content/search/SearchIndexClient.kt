package com.onix.content.search

import com.onix.search.v1.SearchIndexGrpc
import com.onix.search.v1.IngestEventsRequest
import com.onix.search.v1.IndexEvent
import com.onix.search.v2.SearchServiceGrpc
import com.onix.search.v2.SearchRequest as SearchRequestV2
import com.onix.search.v2.SearchMode
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface SearchIndexClient {
    fun search(collection: String, query: String, limit: Int): SearchIndexResult
    fun ingest(events: List<SearchOutboxEvent>): SearchIngestResult = SearchIngestResult()

    companion object {
        fun noop(): SearchIndexClient = object : SearchIndexClient {
            override fun search(collection: String, query: String, limit: Int): SearchIndexResult =
                SearchIndexResult(error = "Search index is not configured")
            override fun ingest(events: List<SearchOutboxEvent>): SearchIngestResult =
                SearchIngestResult(error = "Search index is not configured")
        }
    }
}

data class SearchOutboxEvent(
    val id: String,
    val eventId: String,
    val operation: String,
    val collection: String,
    val documentId: String,
    val revision: Long,
    val documentJson: String,
    val occurredAt: String
)

data class SearchIngestItemResult(
    val eventId: String,
    val status: String,
    val message: String? = null
)

data class SearchIngestResult(
    val results: List<SearchIngestItemResult> = emptyList(),
    val error: String? = null
)

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
    target: String?,
    private val apiKey: String,
    private val tls: Boolean = false,
    private val trustCert: String? = null,
    private val clientCert: String? = null,
    private val clientKey: String? = null
) : SearchIndexClient, AutoCloseable {
    private val channel: ManagedChannel? = target?.takeIf(String::isNotBlank)?.let { channel(it, tls, trustCert, clientCert, clientKey) }
    private val stub: SearchIndexGrpc.SearchIndexBlockingStub? = channel?.let(SearchIndexGrpc::newBlockingStub)
    private val searchV2: SearchServiceGrpc.SearchServiceBlockingStub? = channel?.let(SearchServiceGrpc::newBlockingStub)

    override fun search(collection: String, query: String, limit: Int): SearchIndexResult {
        if (query.isBlank()) return SearchIndexResult()
        val current = searchV2 ?: return SearchIndexResult(error = "Search index gRPC target is not configured")
        val response = runCatching {
            current.authed().search(
                SearchRequestV2.newBuilder()
                    .setCollection(collection)
                    .setQuery(query)
                    .setMode(SearchMode.SEARCH_MODE_AUTO)
                    .setLimit(limit.coerceIn(1, 100))
                    .setExplain(true)
                    .build()
            )
        }.getOrElse { error ->
            val cause = error as? StatusRuntimeException
            return SearchIndexResult(error = if (cause != null) {
                "Search index gRPC returned ${cause.status.code}: ${cause.status.description.orEmpty()}"
            } else {
                error.message ?: "Search index unavailable"
            })
        }
        return SearchIndexResult(
            hits = response.hitsList.map {
                SearchIndexHit(id = it.id, score = it.score, snippet = searchSnippet(it.documentJson))
            },
            error = response.error.takeIf(String::isNotBlank)
        )
    }

    override fun ingest(events: List<SearchOutboxEvent>): SearchIngestResult {
        if (events.isEmpty()) return SearchIngestResult()
        val current = stub ?: return SearchIngestResult(error = "Search index gRPC target is not configured")
        val response = runCatching {
            current.authed().ingestEvents(
                IngestEventsRequest.newBuilder()
                    .addAllEvents(events.map { event ->
                        IndexEvent.newBuilder()
                            .setEventId(event.eventId)
                            .setSourceService("content")
                            .setOperation(event.operation)
                            .setCollection(event.collection)
                            .setDocumentId(event.documentId)
                            .setRevision(event.revision)
                            .setDocumentJson(event.documentJson)
                            .setOccurredAt(event.occurredAt)
                            .build()
                    })
                    .build()
            )
        }.getOrElse { error ->
            val cause = error as? StatusRuntimeException
            return SearchIngestResult(error = if (cause != null) {
                "Search index gRPC returned ${cause.status.code}: ${cause.status.description.orEmpty()}"
            } else {
                error.message ?: "Search index unavailable"
            })
        }
        return SearchIngestResult(
            results = response.resultsList.map {
                SearchIngestItemResult(
                    eventId = it.eventId,
                    status = it.status,
                    message = it.message.takeIf(String::isNotBlank)
                )
            }
        )
    }

    override fun close() {
        channel?.shutdown()
    }

    private fun SearchIndexGrpc.SearchIndexBlockingStub.authed(): SearchIndexGrpc.SearchIndexBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION_KEY, "Bearer $apiKey")
            put(SERVICE_KEY, "content")
        }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun SearchServiceGrpc.SearchServiceBlockingStub.authed(): SearchServiceGrpc.SearchServiceBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION_KEY, "Bearer $apiKey")
            put(SERVICE_KEY, "content")
        }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val SERVICE_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-onix-service", Metadata.ASCII_STRING_MARSHALLER)
    }
}

private fun searchSnippet(documentJson: String): String? = runCatching {
    val document = Json.parseToJsonElement(documentJson).jsonObject
    listOf("title", "content", "description")
        .firstNotNullOfOrNull { key -> document[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
        ?.take(240)
}.getOrNull()

private fun channel(target: String, tls: Boolean, trustCert: String?, clientCert: String?, clientKey: String?): ManagedChannel {
    val (host, port) = parseTarget(target)
    val builder = NettyChannelBuilder.forAddress(host, port)
    if (!tls) return builder.usePlaintext().build()
    val ssl = GrpcSslContexts.forClient()
    trustCert?.let { ssl.trustManager(File(it)) }
    if (!clientCert.isNullOrBlank() && !clientKey.isNullOrBlank()) {
        ssl.keyManager(File(clientCert), File(clientKey))
    }
    return builder.sslContext(ssl.build()).build()
}

private fun parseTarget(target: String): Pair<String, Int> {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 9094)
}
