package com.onix.content.grpc

import com.onix.content.account.AccountClient
import com.onix.content.domain.*
import com.onix.content.service.ContentService
import com.onix.content.v1.CollectionItemRef as GrpcCollectionItemRef
import com.onix.content.v1.CollectionItemView
import com.onix.content.v1.ContentBlock as GrpcContentBlock
import com.onix.content.v1.ContentProviderGrpc
import com.onix.content.v1.OwnerSectionRequest
import com.onix.content.v1.OwnerSectionResponse
import com.onix.content.v1.ProfileOwner
import com.onix.content.v1.ProfilePost
import com.onix.content.v1.ProfileStoryDetail
import com.onix.content.v1.ResolveCollectionItemsRequest
import com.onix.content.v1.ResolveCollectionItemsResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class ContentProviderGrpcService(
    private val content: ContentService,
    private val account: AccountClient
) : ContentProviderGrpc.ContentProviderImplBase() {
    private val json = Json { encodeDefaults = true }

    override fun resolveCollectionItems(
        request: ResolveCollectionItemsRequest,
        observer: StreamObserver<ResolveCollectionItemsResponse>
    ) = unary(observer) {
        val token = accessToken()
        val viewer = OwnerRef(request.viewerType.ownerType(), request.viewerId)
        val authorResolver = authorResolver(token)
        val errors = mutableListOf<String>()
        val items = request.refsList.mapNotNull { ref ->
            when {
                ref.serviceKey != "content" -> {
                    errors.add("Unsupported provider ${ref.serviceKey}")
                    null
                }
                ref.itemType.lowercase() != "post" -> {
                    errors.add("Unsupported content item type ${ref.itemType}")
                    null
                }
                else -> {
                    val post = content.post(ref.itemId, viewer, authorResolver) ?: return@mapNotNull null
                    val visibility = account.ownerVisibility(OwnerRef(post.ownerType, post.ownerId), viewer, token)
                    if (!canViewPost(post, visibility)) null else post.toItemView(ref)
                }
            }
        }.take(request.limit.takeIf { it > 0 } ?: Int.MAX_VALUE)
        ResolveCollectionItemsResponse.newBuilder()
            .addAllItems(items)
            .addAllPartialErrors(errors.distinct())
            .build()
    }

    override fun listOwnerSection(
        request: OwnerSectionRequest,
        observer: StreamObserver<OwnerSectionResponse>
    ) = unary(observer) {
        val token = accessToken()
        val viewer = OwnerRef(request.viewerType.ownerType(), request.viewerId)
        val owner = OwnerRef(request.ownerType.ownerType(), request.ownerId)
        val visibility = account.ownerVisibility(owner, viewer, token)
        val authorResolver = authorResolver(token)
        val limit = request.limit.takeIf { it > 0 } ?: 40
        when (request.buttonKey) {
            "posts" -> {
                val posts = content.profileContent(
                    ownerId = owner.ownerId,
                    visibility = visibility,
                    postLimit = limit,
                    storyLimit = 1,
                    authorResolver = authorResolver,
                    visibilityResolver = visibilityResolver(viewer, token)
                ).posts
                OwnerSectionResponse.newBuilder()
                    .addAllPosts(posts.map { it.toGrpc() })
                    .addAllItems(posts.map { it.toItemView(GrpcCollectionItemRef.newBuilder()
                        .setServiceKey("content")
                        .setItemType("post")
                        .setItemId(it.id)
                        .build()) })
                    .build()
            }
            "story_archive" -> {
                val cursor = request.cursor.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val ownerAccount = authorResolver(owner.key())
                val archive = content.storyArchive(owner.ownerId, visibility, ownerAccount, limit, cursor)
                OwnerSectionResponse.newBuilder()
                    .addAllStories(archive.stories.map { it.toStoryDetail() })
                    .setNextCursor(archive.nextCursor.orEmpty())
                    .build()
            }
            else -> OwnerSectionResponse.newBuilder()
                .addPartialErrors("Unsupported section ${request.buttonKey}")
                .build()
        }
    }

    private fun <T> unary(observer: StreamObserver<T>, block: () -> T) {
        try {
            observer.onNext(block())
            observer.onCompleted()
        } catch (error: StatusRuntimeException) {
            observer.onError(error)
        } catch (error: Throwable) {
            observer.onError(Status.INTERNAL.withDescription(error.message ?: "Content provider failed").asRuntimeException())
        }
    }

    private fun accessToken(): String =
        AUTH_CONTEXT.get()?.removePrefix("Bearer ")?.takeIf(String::isNotBlank)
            ?: throw Status.UNAUTHENTICATED.withDescription("Authorization bearer token is required").asRuntimeException()

    private fun authorResolver(token: String): (String) -> AccountUser? {
        val cache = mutableMapOf<String, AccountUser?>()
        return { ownerKey -> cache.getOrPut(ownerKey) { account.getOwner(ownerKey.toOwnerRef(), token) } }
    }

    private fun visibilityResolver(viewer: OwnerRef, token: String): (String) -> AccountVisibility {
        val cache = mutableMapOf<String, AccountVisibility>()
        return { ownerKey -> cache.getOrPut(ownerKey) { account.ownerVisibility(ownerKey.toOwnerRef(), viewer, token) } }
    }

    private fun Post.toItemView(ref: GrpcCollectionItemRef): CollectionItemView {
        val preview = blocks.filter { it.type == ContentBlockType.IMAGE || it.type == ContentBlockType.VIDEO }.take(3)
        return CollectionItemView.newBuilder()
            .setRef(ref)
            .setTitle(title ?: text.lineSequence().firstOrNull()?.take(80).orEmpty())
            .setText(text)
            .addAllPreviewBlocks(preview.map { it.toGrpc() })
            .setUrl("/p/$id")
            .setCreatedAt(createdAt.toString())
            .setOwner(author?.toGrpc() ?: ProfileOwner.getDefaultInstance())
            .setPost(toGrpc())
            .putAllMeta(mapOf("visibility" to visibility.name, "likeCount" to likeCount.toString()))
            .build()
    }

    private fun Post.toGrpc(): ProfilePost =
        ProfilePost.newBuilder()
            .setId(id)
            .setAuthorId(authorId)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setAuthor(author?.toGrpc() ?: ProfileOwner.getDefaultInstance())
            .setTitle(title.orEmpty())
            .setText(text)
            .addAllTags(tags)
            .setCreatedAt(createdAt.toString())
            .setLikeCount(likeCount)
            .setLikedByViewer(likedByViewer)
            .addAllBlocks(blocks.map { it.toGrpc() })
            .build()

    private fun Story.toStoryDetail(): ProfileStoryDetail =
        ProfileStoryDetail.newBuilder()
            .setId(id)
            .setAuthorId(authorId)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setAuthor(author?.toGrpc() ?: ProfileOwner.getDefaultInstance())
            .setVisibility(visibility.name)
            .addAllBlocks(blocks.map { it.toGrpc() })
            .setDurationMs(durationMs)
            .setMediaDurationMs(mediaDurationMs ?: 0)
            .setCloseFriends(closeFriends)
            .setArchived(archived)
            .setLikeCount(likeCount)
            .setLikedByViewer(likedByViewer)
            .setRemainingLifeSeconds(remainingLifeSeconds ?: 0)
            .setCreatedAt(createdAt.toString())
            .setExpiresAt(expiresAt.toString())
            .build()

    private fun ContentBlock.toGrpc(): GrpcContentBlock =
        GrpcContentBlock.newBuilder()
            .setId(id.orEmpty())
            .setType(type.name)
            .setDataJson(json.encodeToString(data))
            .build()
}

private fun canViewPost(post: Post, visibility: AccountVisibility): Boolean {
    if (visibility.isBlocked || post.status != ContentStatus.ACTIVE) return false
    if (post.ownerId == visibility.viewerId && post.ownerType == visibility.viewerType) return true
    if (visibility.ownerId != post.ownerId || visibility.ownerType != post.ownerType) return false
    return when (post.visibility) {
        Visibility.PUBLIC -> visibility.canSeePrivateContent
        Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
    }
}

private fun AccountUser.toGrpc(): ProfileOwner =
    ProfileOwner.newBuilder()
        .setId(id)
        .setOwnerType(ownerType.name)
        .setUsername(username)
        .setDisplayName(displayName.orEmpty())
        .setFirstName(firstName.orEmpty())
        .setLastName(lastName.orEmpty())
        .setAvatarUrl(avatarUrl.orEmpty())
        .build()

private fun String.ownerType(): OwnerType =
    if (this == OwnerType.ORGANIZATION.name) OwnerType.ORGANIZATION else OwnerType.USER

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(parts[0].ownerType(), parts[1]) else OwnerRef(OwnerType.USER, this)
}
