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
enum class ContentStatus { ACTIVE, ARCHIVED, DELETED }

@Serializable
enum class ContentBlockType { TEXT, IMAGE, VIDEO, AUDIO, FILE }

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class AccountUser(
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
data class AccountSocialGraph(
    val followingIds: List<String> = emptyList(),
    val friendIds: List<String> = emptyList(),
    val blockedIds: List<String> = emptyList()
)

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
    val author: AccountUser? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
)

@Serializable
data class Story(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val author: AccountUser? = null,
    val blocks: List<ContentBlock> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    val durationMs: Long = 5_000,
    val mediaDurationMs: Long? = null,
    val closeFriends: Boolean = visibility == Visibility.CLOSE_FRIENDS,
    val archived: Boolean = status == ContentStatus.ARCHIVED,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    val remainingLifeSeconds: Long? = null,
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
    val author: AccountUser? = null,
    val parentId: String? = null,
    val text: String,
    val blocks: List<ContentBlock> = emptyList(),
    val status: ContentStatus = ContentStatus.ACTIVE,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
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
data class FeedCell(
    val q: Int,
    val r: Int
)

@Serializable
enum class FeedEmphasis { hero, standard, compact }

@Serializable
data class RecommendationFeedInput(
    val chunkX: Int = 0,
    val chunkY: Int = 0,
    val sessionSeed: String = "default",
    val limit: Int = 12
)

@Serializable
data class RecommendationFeedItem(
    val post: Post,
    val score: Double,
    val reasons: List<String> = emptyList(),
    val cell: FeedCell,
    val emphasis: FeedEmphasis = FeedEmphasis.compact
)

@Serializable
data class RecommendationFeedResponse(
    val chunkX: Int,
    val chunkY: Int,
    val sessionSeed: String,
    val items: List<RecommendationFeedItem> = emptyList()
)

@Serializable
data class PostReactionState(
    val postId: String,
    val liked: Boolean,
    val likeCount: Long
)

@Serializable
data class StoryReactionState(
    val storyId: String,
    val liked: Boolean,
    val likeCount: Long
)

@Serializable
data class CommentReactionState(
    val commentId: String,
    val liked: Boolean,
    val likeCount: Long
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
    val author: AccountUser? = null,
    val avatarUrl: String? = null,
    val storyIds: List<String>,
    val activeCount: Int,
    val seen: Boolean = false,
    val closeFriends: Boolean = false,
    val isViewer: Boolean = false,
    @Serializable(with = InstantIsoSerializer::class)
    val oldestAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val latestAt: Instant
)

@Serializable
data class StoryGroup(
    val authorId: String,
    val authorName: String,
    val author: AccountUser? = null,
    val avatarUrl: String? = null,
    val stories: List<Story> = emptyList(),
    val startStoryId: String? = null,
    val archive: Boolean = false
)

@Serializable
data class StoryArchiveResponse(
    val ownerId: String,
    val owner: AccountUser? = null,
    val stories: List<Story> = emptyList(),
    val cursor: String? = null,
    val nextCursor: String? = null
)

@Serializable
data class CreatePostInput(
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
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
    val text: String,
    val blocks: List<ContentBlock> = emptyList(),
    val parentId: String? = null,
)

fun textBlock(text: String): ContentBlock = ContentBlock(
    type = ContentBlockType.TEXT,
    data = JsonObject(mapOf("text" to JsonPrimitive(text)))
)

fun ContentBlock.searchText(): String = when (val value: JsonElement? = data["text"]) {
    is JsonPrimitive -> value.content
    else -> ""
}
