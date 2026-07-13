package com.onix.content.search

import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.math.min

class SearchOutboxWorker(
    private val dataSource: DataSource,
    private val searchIndex: SearchIndexClient,
    private val batchSize: Int = 50,
    private val pollInterval: Duration = Duration.ofSeconds(1),
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val maxRetries: Int = 8
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start(): SearchOutboxWorker {
        if (!running.compareAndSet(false, true)) return this
        thread = Thread(::loop, "content-search-outbox-worker").apply {
            isDaemon = true
            start()
        }
        return this
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread?.join(5_000)
    }

    private fun loop() {
        while (running.get()) {
            runCatching { processOnce() }
                .onFailure { System.err.println("Search outbox worker failed: ${it.message}") }
            runCatching { Thread.sleep(pollInterval.toMillis()) }
        }
    }

    fun processOnce(): Int {
        val events = leaseBatch()
        if (events.isEmpty()) return 0
        val response = searchIndex.ingest(events)
        if (response.error != null) {
            events.forEach { markRetryOrDead(it.id, response.error) }
            return events.size
        }
        val results = response.results.associateBy { it.eventId }
        events.forEach { event ->
            val result = results[event.eventId]
            when (result?.status) {
                "accepted", "duplicate" -> markAccepted(event.id)
                "rejected" -> markDead(event.id, result.message ?: "Search rejected event")
                else -> markRetryOrDead(event.id, result?.message ?: "Search did not return ingest status")
            }
        }
        return events.size
    }

    private fun leaseBatch(): List<SearchOutboxEvent> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val events = connection.prepareStatement(
                    """
                    WITH candidate AS (
                        SELECT id
                        FROM content.outbox_events
                        WHERE target_service = 'search'
                          AND (
                              (status IN ('pending', 'retry') AND next_attempt_at <= NOW())
                              OR (status = 'leased' AND leased_until <= NOW())
                          )
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE content.outbox_events AS event
                    SET status = 'leased',
                        leased_until = NOW() + (? * INTERVAL '1 second'),
                        updated_at = NOW()
                    FROM candidate
                    WHERE event.id = candidate.id
                    RETURNING event.id::text,
                              event.collection,
                              event.document_id,
                              event.operation,
                              event.revision,
                              event.payload_json::text,
                              event.created_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, batchSize.coerceAtLeast(1))
                    statement.setLong(2, leaseDuration.seconds.coerceAtLeast(1))
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(rows.toSearchOutboxEvent())
                        }
                    }
                }
                connection.commit()
                events
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }

    private fun markAccepted(id: String) {
        updateEvent(
            id = id,
            sql = """
                UPDATE content.outbox_events
                SET status = 'accepted',
                    leased_until = NULL,
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = ?::uuid
            """.trimIndent()
        )
    }

    private fun markDead(id: String, error: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE content.outbox_events
                SET status = 'dead',
                    attempts = attempts + 1,
                    leased_until = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = ?::uuid
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, error.take(2_000))
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
    }

    private fun markRetryOrDead(id: String, error: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE content.outbox_events
                SET status = CASE WHEN attempts + 1 >= ? THEN 'dead' ELSE 'retry' END,
                    attempts = attempts + 1,
                    next_attempt_at = CASE
                        WHEN attempts + 1 >= ? THEN next_attempt_at
                        ELSE NOW() + (? * INTERVAL '1 second')
                    END,
                    leased_until = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = ?::uuid
                """.trimIndent()
            ).use { statement ->
                val attempts = currentAttempts(connection, id)
                statement.setInt(1, maxRetries.coerceAtLeast(1))
                statement.setInt(2, maxRetries.coerceAtLeast(1))
                statement.setLong(3, backoffSeconds(attempts))
                statement.setString(4, error.take(2_000))
                statement.setString(5, id)
                statement.executeUpdate()
            }
        }
    }

    private fun updateEvent(id: String, sql: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    private fun currentAttempts(connection: Connection, id: String): Int =
        connection.prepareStatement("SELECT attempts FROM content.outbox_events WHERE id = ?::uuid").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt("attempts") else 0
            }
        }

    private fun backoffSeconds(attempts: Int): Long =
        min(300L, 1L shl attempts.coerceIn(0, 8))
}

private fun ResultSet.toSearchOutboxEvent(): SearchOutboxEvent {
    val id = getString("id")
    return SearchOutboxEvent(
        id = id,
        eventId = id,
        operation = getString("operation"),
        collection = getString("collection"),
        documentId = getString("document_id"),
        revision = getLong("revision"),
        documentJson = getString("payload_json"),
        occurredAt = getTimestamp("created_at").toInstant().toString()
    )
}
