package com.onix.profile.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class AccountSearchUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null
)

@Serializable
data class RelatedUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val relationship: Relationship? = null
)

@Serializable
data class UserPageResponse(
    val items: List<RelatedUser> = emptyList(),
    val totalCount: Int = 0
)

@Serializable
data class SocialCanvasResponse(
    val owner: AccountProfile,
    val filter: String,
    val items: List<RelatedUser> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val limit: Int = 40
)

@Serializable
data class AccountUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class CurrentActor(
    val user: SessionUser,
    val activeOwner: AccountUser
)

@Serializable
data class Relationship(
    val isFollowing: Boolean = false,
    val isFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    val isBlocked: Boolean = false,
    val hasPendingRequest: Boolean = false
)

@Serializable
data class BirthdayParts(val day: Int, val month: Int)

@Serializable
data class SocialLink(val label: String, val url: String)

@Serializable
data class ContentBlock(
    val id: String? = null,
    val type: String,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class AccountProfile(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val bio: String? = null,
    val birthday: BirthdayParts? = null,
    val socialLinks: List<SocialLink> = emptyList(),
    val avatarUrl: String? = null,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val isPrivate: Boolean = false,
    val relationship: Relationship = Relationship()
)

@Serializable
data class ProfileContentPost(
    val id: String,
    val authorId: String? = null,
    val ownerType: String = "USER",
    val ownerId: String? = authorId,
    val author: AccountUser? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    val createdAt: String? = null
)

@Serializable
data class ProfileContentStory(
    val id: String,
    val visibility: String,
    val expiresAt: String? = null
)

@Serializable
data class ProfileContentComment(
    val id: String,
    val postId: String,
    val text: String,
    val createdAt: String? = null
)

@Serializable
data class ProfileContentCollection(
    val id: String,
    val ownerType: String = "USER",
    val ownerId: String,
    val title: String,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: String = "PRIVATE",
    val itemCount: Int = 0,
    val previewBlocks: List<ContentBlock> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ProfileContentSummary(
    val posts: List<ProfileContentPost> = emptyList(),
    val stories: List<ProfileContentStory> = emptyList(),
    val comments: List<ProfileContentComment> = emptyList(),
    val collections: List<ProfileContentCollection> = emptyList()
)

@Serializable
data class CanvasPosition(val x: Double, val y: Double)

@Serializable
data class CanvasNode(
    val id: String,
    val type: String,
    val position: CanvasPosition,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CanvasEdge(
    val id: String,
    val source: String,
    val target: String
)

@Serializable
data class CanvasViewport(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0
)

@Serializable
data class ProfilePermissions(
    val owner: Boolean,
    val canFollow: Boolean
)

@Serializable
data class ProfileCanvasResponse(
    val status: String,
    val profile: AccountProfile? = null,
    val content: ProfileContentSummary = ProfileContentSummary(),
    val relationship: Relationship? = null,
    val nodes: List<CanvasNode> = emptyList(),
    val edges: List<CanvasEdge> = emptyList(),
    val permissions: ProfilePermissions = ProfilePermissions(owner = false, canFollow = false),
    val viewport: CanvasViewport = CanvasViewport()
)

@Serializable
data class SessionMeResponse(
    val user: SessionUser
)

@Serializable
data class AuthRequiredResponse(
    val code: String = "AUTH_REQUIRED",
    val loginUrl: String
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

@Serializable
data class FollowResponse(
    val relationship: Relationship
)

@Serializable
data class SearchResponse(
    val query: String,
    val items: List<SearchItem> = emptyList(),
    val nextCursor: String? = null,
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class SearchItem(
    val type: String,
    val id: String,
    val title: String? = null,
    val snippet: String? = null,
    val owner: AccountUser? = null,
    val url: String,
    val score: Double = 0.0,
    val createdAt: String? = null,
    val postId: String? = null,
    val commentId: String? = null,
    val tags: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class SearchSuggestResponse(
    val query: String,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class SearchSuggestion(
    val type: String,
    val value: String,
    val label: String,
    val owner: AccountSearchUser? = null
)
