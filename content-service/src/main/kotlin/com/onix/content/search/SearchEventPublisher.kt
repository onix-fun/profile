package com.onix.content.search

import com.onix.content.domain.Comment
import com.onix.content.domain.Post
import com.onix.content.domain.SavedCollection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.time.Instant
import java.util.UUID

interface SearchEventPublisher {
    fun postUpsert(post: Post)
    fun commentUpsert(comment: Comment)
    fun collectionUpsert(collection: SavedCollection)
    fun collectionDelete(collectionId: String)

    companion object {
        fun noop(): SearchEventPublisher = object : SearchEventPublisher {
            override fun postUpsert(post: Post) = Unit
            override fun commentUpsert(comment: Comment) = Unit
            override fun collectionUpsert(collection: SavedCollection) = Unit
            override fun collectionDelete(collectionId: String) = Unit
        }
    }
}

class RabbitSearchEventPublisher(private val rabbitmqUrl: String?) : SearchEventPublisher {
    private val json = Json { encodeDefaults = true }

    override fun postUpsert(post: Post) {
        publish(
            collection = "posts",
            documentId = post.id,
            revision = post.updatedAt.toEpochMilli(),
            document = JsonObject(
                mapOf(
                    "author_id" to JsonPrimitive(post.authorId),
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
        publish(
            collection = "comments",
            documentId = comment.id,
            revision = comment.updatedAt.toEpochMilli(),
            document = JsonObject(
                mapOf(
                    "post_id" to JsonPrimitive(comment.postId),
                    "author_id" to JsonPrimitive(comment.authorId),
                    "content" to JsonPrimitive(comment.text),
                    "updated_at" to JsonPrimitive(comment.updatedAt.toString())
                )
            )
        )
    }

    override fun collectionUpsert(collection: SavedCollection) {
        publish(
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
        publish(
            collection = "collections",
            documentId = collectionId,
            revision = Instant.now().toEpochMilli(),
            operation = "delete",
            document = JsonObject(emptyMap())
        )
    }

    private fun publish(collection: String, documentId: String, revision: Long, operation: String = "upsert", document: JsonObject) {
        if (rabbitmqUrl.isNullOrBlank()) return
        val event = IndexEvent(
            event_id = UUID.randomUUID().toString(),
            operation = operation,
            collection = collection,
            document_id = documentId,
            revision = revision.coerceAtLeast(1),
            document = document,
            occurred_at = Instant.now().toString()
        )
        try {
            val factory = ConnectionFactory().apply {
                setUri(rabbitmqUrl)
                val path = URI.create(rabbitmqUrl).rawPath
                if (path.isNullOrBlank() || path == "/") {
                    virtualHost = "/"
                }
            }
            factory.newConnection().use { connection ->
                connection.createChannel().use { channel ->
                    val exchange = "search.$collection.events"
                    channel.exchangeDeclare(exchange, "direct", true)
                    channel.basicPublish(exchange, "search.$collection.events.queue", null, json.encodeToString(event).toByteArray())
                }
            }
        } catch (error: Exception) {
            System.err.println("Search event publish failed for $collection/$documentId: ${error.message}")
        }
    }
}

@Serializable
private data class IndexEvent(
    val event_id: String,
    val operation: String,
    val collection: String,
    val document_id: String,
    val revision: Long,
    val document: JsonObject,
    val occurred_at: String
)
