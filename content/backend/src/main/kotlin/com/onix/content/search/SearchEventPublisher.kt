package com.onix.content.search

import com.onix.content.domain.Comment
import com.onix.content.domain.Post
import com.onix.content.domain.SavedCollection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface SearchEventPublisher {
    fun postUpsert(post: Post)
    fun postDelete(postId: String)
    fun commentUpsert(comment: Comment)
    fun commentDelete(commentId: String)
    fun collectionUpsert(collection: SavedCollection)
    fun collectionDelete(collectionId: String)

    companion object {
        fun noop(): SearchEventPublisher = object : SearchEventPublisher {
            override fun postUpsert(post: Post) = Unit
            override fun postDelete(postId: String) = Unit
            override fun commentUpsert(comment: Comment) = Unit
            override fun commentDelete(commentId: String) = Unit
            override fun collectionUpsert(collection: SavedCollection) = Unit
            override fun collectionDelete(collectionId: String) = Unit
        }
    }
}

class OutboxSearchEventPublisher(private val dataSource: DataSource?) : SearchEventPublisher {
    private val json = Json { encodeDefaults = true }

    override fun postUpsert(post: Post) {
        enqueue(
            collection = "posts",
            documentId = post.id,
            revision = post.updatedAt.toEpochMilli(),
            document = JsonObject(
                mapOf(
                    "author_id" to JsonPrimitive(post.authorId),
                    "owner_type" to JsonPrimitive(post.ownerType.name),
                    "owner_id" to JsonPrimitive(post.ownerId),
                    "title" to JsonPrimitive(post.title ?: ""),
                    "content" to JsonPrimitive(post.text),
                    "tags" to JsonPrimitive(post.tags.joinToString(" ")),
                    "visibility" to JsonPrimitive(post.visibility.name),
                    "updated_at" to JsonPrimitive(post.updatedAt.toString())
                )
            )
        )
    }

    override fun commentUpsert(comment: Comment) {
        enqueue(
            collection = "comments",
            documentId = comment.id,
            revision = comment.updatedAt.toEpochMilli(),
            document = JsonObject(
                mapOf(
                    "post_id" to JsonPrimitive(comment.postId),
                    "author_id" to JsonPrimitive(comment.authorId),
                    "owner_type" to JsonPrimitive(comment.ownerType.name),
                    "owner_id" to JsonPrimitive(comment.ownerId),
                    "content" to JsonPrimitive(comment.text),
                    "updated_at" to JsonPrimitive(comment.updatedAt.toString())
                )
            )
        )
    }

    override fun postDelete(postId: String) {
        enqueue(
            collection = "posts",
            documentId = postId,
            revision = Instant.now().toEpochMilli(),
            operation = "delete",
            document = JsonObject(emptyMap())
        )
    }

    override fun commentDelete(commentId: String) {
        enqueue(
            collection = "comments",
            documentId = commentId,
            revision = Instant.now().toEpochMilli(),
            operation = "delete",
            document = JsonObject(emptyMap())
        )
    }

    override fun collectionUpsert(collection: SavedCollection) {
        enqueue(
            collection = "collections",
            documentId = collection.id,
            revision = collection.updatedAt.toEpochMilli(),
            document = JsonObject(
                mapOf(
                    "owner_type" to JsonPrimitive(collection.ownerType.name),
                    "owner_id" to JsonPrimitive(collection.ownerId),
                    "title" to JsonPrimitive(collection.title),
                    "description" to JsonPrimitive(collection.description ?: ""),
                    "visibility" to JsonPrimitive(collection.visibility.name),
                    "item_count" to JsonPrimitive(collection.itemCount),
                    "updated_at" to JsonPrimitive(collection.updatedAt.toString())
                )
            )
        )
    }

    override fun collectionDelete(collectionId: String) {
        enqueue(
            collection = "collections",
            documentId = collectionId,
            revision = Instant.now().toEpochMilli(),
            operation = "delete",
            document = JsonObject(emptyMap())
        )
    }

    private fun enqueue(
        collection: String,
        documentId: String,
        revision: Long,
        operation: String = "upsert",
        document: JsonObject
    ) {
        val current = dataSource ?: return
        val stableRevision = revision.coerceAtLeast(1)
        val eventId = UUID.randomUUID().toString()
        val idempotencyKey = "$collection:$documentId:$stableRevision:$operation"
        current.connection.use { connection ->
            connection.insertOutboxEvent(
                id = eventId,
                idempotencyKey = idempotencyKey,
                collection = collection,
                documentId = documentId,
                operation = operation,
                revision = stableRevision,
                payloadJson = json.encodeToString(document)
            )
        }
    }
}

private fun Connection.insertOutboxEvent(
    id: String,
    idempotencyKey: String,
    collection: String,
    documentId: String,
    operation: String,
    revision: Long,
    payloadJson: String
) {
    prepareStatement(
        """
        INSERT INTO content.outbox_events (
            id,
            idempotency_key,
            target_service,
            event_type,
            collection,
            document_id,
            operation,
            revision,
            payload_json
        )
        VALUES (?::uuid, ?, 'search', 'search.index', ?, ?, ?, ?, ?::jsonb)
        ON CONFLICT (idempotency_key) DO NOTHING
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, id)
        statement.setString(2, idempotencyKey)
        statement.setString(3, collection)
        statement.setString(4, documentId)
        statement.setString(5, operation)
        statement.setLong(6, revision)
        statement.setString(7, payloadJson)
        statement.executeUpdate()
    }
}
