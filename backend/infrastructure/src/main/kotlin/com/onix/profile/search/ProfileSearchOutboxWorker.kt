package com.onix.profile.search

import com.onix.search.contract.IndexEvent
import com.onix.search.contract.IndexOperation
import com.onix.search.contract.IngestEventsRequest
import com.onix.search.contract.IngestStatus
import com.onix.search.contract.SearchServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.sql.ResultSet
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.math.min

class ProfileSearchOutboxWorker(
    private val dataSource: DataSource,
    target: String,
    private val apiKey: String,
    private val pollInterval: Duration = Duration.ofSeconds(1),
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val maxAttempts: Int = 8
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val channel: ManagedChannel = channel(target)
    private val stub = SearchServiceGrpc.newBlockingStub(channel)
    private var thread: Thread? = null

    fun start(): ProfileSearchOutboxWorker {
        if (!running.compareAndSet(false, true)) return this
        thread = Thread(::loop, "profile-search-outbox").apply { isDaemon = true; start() }
        return this
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread?.join(5_000)
        channel.shutdownNow()
        channel.awaitTermination(2, TimeUnit.SECONDS)
    }

    internal fun processOnce(): Int {
        val events = leaseBatch()
        if (events.isEmpty()) return 0
        val result = runCatching {
            authedStub().withDeadlineAfter(5, TimeUnit.SECONDS).ingestEvents(
                IngestEventsRequest.newBuilder().addAllEvents(events.map(ProfileOutboxEvent::toProto)).build()
            )
        }
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Search delivery failed"
            events.forEach { retry(it.eventId, message) }
            return events.size
        }
        val responses = result.getOrThrow().resultsList.associateBy { it.eventId }
        events.forEach { event ->
            val response = responses[event.eventId]
            when (response?.status) {
                IngestStatus.INGEST_STATUS_ACCEPTED,
                IngestStatus.INGEST_STATUS_DUPLICATE,
                IngestStatus.INGEST_STATUS_STALE -> delivered(event.eventId)
                IngestStatus.INGEST_STATUS_REJECTED -> failed(event.eventId, response.message)
                else -> retry(event.eventId, response?.message ?: "Search returned no result")
            }
        }
        return events.size
    }

    private fun loop() {
        while (running.get()) {
            runCatching(::processOnce).onFailure { System.err.println("Profile search outbox failed: ${it.message}") }
            try {
                Thread.sleep(pollInterval.toMillis())
            } catch (_: InterruptedException) {
                if (!running.get()) return
            }
        }
    }

    private fun leaseBatch(): List<ProfileOutboxEvent> = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val events = connection.prepareStatement(
                """
                WITH candidates AS (
                    SELECT event_id
                    FROM profile.outbox_events
                    WHERE ((status = 'PENDING' AND next_attempt_at <= NOW())
                        OR (status = 'LEASED' AND leased_until <= NOW()))
                    ORDER BY created_at
                    LIMIT 50
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE profile.outbox_events AS event
                SET status = 'LEASED', leased_until = NOW() + (? * INTERVAL '1 second')
                FROM candidates
                WHERE event.event_id = candidates.event_id
                RETURNING event.event_id::text, event.aggregate_type, event.aggregate_id::text,
                    event.revision, event.created_at, event.payload_json::text
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, leaseDuration.seconds.coerceAtLeast(1))
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toEvent()) } }
            }
            connection.commit()
            events
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun delivered(eventId: String) = update(
        "UPDATE profile.outbox_events SET status = 'DELIVERED', leased_until = NULL, last_error = NULL WHERE event_id = ?::uuid",
        eventId
    )

    private fun failed(eventId: String, message: String) = update(
        "UPDATE profile.outbox_events SET status = 'FAILED', attempts = attempts + 1, leased_until = NULL, last_error = ? WHERE event_id = ?::uuid",
        eventId,
        message
    )

    private fun retry(eventId: String, message: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE profile.outbox_events
                SET status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    attempts = attempts + 1,
                    next_attempt_at = NOW() + (? * INTERVAL '1 second'),
                    leased_until = NULL,
                    last_error = ?
                WHERE event_id = ?::uuid
                """.trimIndent()
            ).use { statement ->
                val attempts = attempts(connection, eventId)
                statement.setInt(1, maxAttempts)
                statement.setLong(2, min(300L, 1L shl attempts.coerceIn(0, 8)))
                statement.setString(3, message.take(2_000))
                statement.setString(4, eventId)
                statement.executeUpdate()
            }
        }
    }

    private fun attempts(connection: java.sql.Connection, eventId: String): Int =
        connection.prepareStatement("SELECT attempts FROM profile.outbox_events WHERE event_id = ?::uuid").use { statement ->
            statement.setString(1, eventId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    private fun update(sql: String, eventId: String, message: String? = null) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                if (message == null) statement.setString(1, eventId) else {
                    statement.setString(1, message.take(2_000))
                    statement.setString(2, eventId)
                }
                statement.executeUpdate()
            }
        }
    }

    private fun authedStub(): SearchServiceGrpc.SearchServiceBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION, "Bearer $apiKey")
            put(SERVICE, "profile")
        }
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private companion object {
        val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val SERVICE: Metadata.Key<String> = Metadata.Key.of("x-onix-service", Metadata.ASCII_STRING_MARSHALLER)
    }
}

private data class ProfileOutboxEvent(
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val revision: Long,
    val occurredAt: String,
    val documentJson: String
) {
    fun toProto(): IndexEvent = IndexEvent.newBuilder()
        .setEventId(eventId)
        .setSourceService("profile")
        .setAggregateType(aggregateType)
        .setAggregateId(aggregateId)
        .setRevision(revision)
        .setOccurredAt(occurredAt)
        .setOperation(IndexOperation.INDEX_OPERATION_UPSERT)
        .setCollection("profiles")
        .setDocumentJson(documentJson)
        .build()
}

private fun ResultSet.toEvent() = ProfileOutboxEvent(
    eventId = getString("event_id"),
    aggregateType = getString("aggregate_type"),
    aggregateId = getString("aggregate_id"),
    revision = getLong("revision"),
    occurredAt = getTimestamp("created_at").toInstant().toString(),
    documentJson = getString("payload_json")
)

private fun channel(target: String): ManagedChannel {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return NettyChannelBuilder.forAddress(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 9094).usePlaintext().build()
}
