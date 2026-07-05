package com.onix.content.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
enum class Visibility { PUBLIC, CLOSE_FRIENDS }

@Serializable
enum class ContentStatus { ACTIVE, DELETED }

@Serializable
enum class ContentBlockType { TEXT, IMAGE, VIDEO, AUDIO }

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class AccountRelationship(
    val isFollowing: Boolean = false,
    val isFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    val isBlocked: Boolean = false,
    val hasPendingRequest: Boolean = false
)

@Serializable
data class AccountVisibility(
    val ownerId: String,
    val viewerId: String?,
    val isPrivate: Boolean = false,
    val relationship: AccountRelationship = AccountRelationship(),
    val isBlocked: Boolean = false,
    val isCloseFriend: Boolean = false
) {
    val canSeePrivateContent: Boolean
        get() = !isBlocked && (viewerId == ownerId || !isPrivate || relationship.isFollowing)
}

@Serializable
data class ContentBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: ContentBlockType,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class Post(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
)

@Serializable
data class Story(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val blocks: List<ContentBlock> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant = createdAt.plusSeconds(24 * 60 * 60)
)

@Serializable
data class Comment(
    val id: String = UUID.randomUUID().toString(),
    val postId: String,
    val authorId: String,
    val parentId: String? = null,
    val text: String,
    val status: ContentStatus = ContentStatus.ACTIVE,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
)

@Serializable
data class FeedItem(
    val post: Post,
    val score: Double,
    val reasons: List<String> = emptyList()
)

@Serializable
data class ProfileContentResponse(
    val posts: List<Post> = emptyList(),
    val stories: List<Story> = emptyList(),
    val comments: List<Comment> = emptyList()
)

@Serializable
data class StoryRailItem(
    val authorId: String,
    val authorName: String,
    val avatarUrl: String? = null,
    val storyIds: List<String>,
    val activeCount: Int,
    val seen: Boolean = false,
    val closeFriends: Boolean = false,
    @Serializable(with = InstantIsoSerializer::class)
    val latestAt: Instant
)

@Serializable
data class CreatePostInput(
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC
)

@Serializable
data class CreateStoryInput(
    val blocks: List<ContentBlock> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC
)

@Serializable
data class CreateCommentInput(
    val postId: String,
    val parentId: String? = null,
    val text: String
)

fun textBlock(text: String): ContentBlock = ContentBlock(
    type = ContentBlockType.TEXT,
    data = JsonObject(mapOf("text" to JsonPrimitive(text)))
)

fun ContentBlock.searchText(): String = when (val value: JsonElement? = data["text"]) {
    is JsonPrimitive -> value.content
    else -> ""
}
