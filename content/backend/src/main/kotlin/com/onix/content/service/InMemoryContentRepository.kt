package com.onix.content.service

import com.onix.content.domain.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryContentRepository : ContentRepository {
    private data class PostView(
        val postId: String,
        val actor: OwnerRef,
        val durationMs: Long,
        val viewedAt: Instant,
        val viewCount: Long
    )

    private data class CollectionItem(
        val collectionId: String,
        val postId: String,
        val addedAt: Instant
    )

    private data class RecommendationSlotKey(
        val viewer: OwnerRef,
        val postId: String
    )

    private val posts = ConcurrentHashMap<String, Post>()
    private val publications = ConcurrentHashMap<String, PostPublication>()
    private val editorDocuments = ConcurrentHashMap<String, PostEditorDocument>()
    private val mediaEventInbox = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var mediaEventSequence = 0L
    private val stories = ConcurrentHashMap<String, Story>()
    private val comments = ConcurrentHashMap<String, Comment>()
    private val collections = ConcurrentHashMap<String, SavedCollection>()
    private val collectionItems = ConcurrentHashMap<Pair<String, String>, CollectionItem>()
    private val mediaReferences = ConcurrentHashMap.newKeySet<ContentMediaReference>()
    private val postLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val storyLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val commentLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val postViews = ConcurrentHashMap<Pair<String, OwnerRef>, PostView>()
    private val recommendationConstellations = ConcurrentHashMap<Pair<OwnerRef, String>, RecommendationConstellation>()
    private val recommendationSlots = ConcurrentHashMap<RecommendationSlotKey, RecommendationPlacement>()
    private val storyViews = ConcurrentHashMap<String, Instant>()
    private val pollVotes = ConcurrentHashMap<Triple<String, String, OwnerRef>, String>()
    private val commentReports = ConcurrentHashMap.newKeySet<CommentReport>()
    private val commentViewerHides = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val postSearchProjections = ConcurrentHashMap<String, PostSearchProjection>()

    override fun savePost(post: Post): Post {
        posts[post.id] = post
        return post
    }

    override fun updatePost(post: Post): Post {
        posts[post.id] = post
        return post
    }

    override fun deletePost(postId: String) {
        posts.remove(postId)
        publications.remove(postId)
        comments.values.filter { it.postId == postId }.map { it.id }.forEach(::deleteComment)
        collectionItems.keys.filter { it.second == postId }.forEach(collectionItems::remove)
        postLikes.removeIf { it.first == postId }
        postViews.keys.filter { it.first == postId }.forEach(postViews::remove)
        recommendationSlots.keys.filter { it.postId == postId }.forEach(recommendationSlots::remove)
        mediaReferences.removeIf { it.ownerType == "post" && it.ownerId == postId }
        postSearchProjections.remove(postId)
    }

    override fun findPost(id: String): Post? = posts[id]?.takeIf { it.status == ContentStatus.ACTIVE }

    override fun findStoredPost(id: String): Post? = posts[id]?.takeIf { it.status != ContentStatus.DELETED }
    override fun findStoredPostByAssetId(assetId: String): Post? = posts.values.firstOrNull { post ->
        post.status != ContentStatus.DELETED && post.assets.any { it.assetId == assetId }
    }

    override fun listDraftPosts(owner: OwnerRef, limit: Int): List<Post> =
        posts.values
            .filter { it.ownerType == owner.ownerType && it.ownerId == owner.ownerId && it.status == ContentStatus.DRAFT }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override fun savePostEditorDocument(document: PostEditorDocument): PostEditorDocument {
        editorDocuments[document.revisionId] = document
        return document
    }

    override fun findPostEditorDocument(revisionId: String): PostEditorDocument? = editorDocuments[revisionId]

    override fun findWorkingPostEditorDocument(postId: String): PostEditorDocument? = editorDocuments.values
        .filter { it.postId == postId && it.state in setOf(PostRevisionState.DRAFT, PostRevisionState.PENDING_SOURCE, PostRevisionState.PROCESSING_MEDIA, PostRevisionState.NEEDS_ACTION) }
        .maxByOrNull { it.revisionNo }

    override fun updatePostEditorRevisionState(revisionId: String, state: PostRevisionState): PostEditorDocument? =
        editorDocuments[revisionId]?.copy(state = state, updatedAt = Instant.now())?.also { editorDocuments[revisionId] = it }

    override fun savePostPublication(publication: PostPublication): PostPublication {
        publications[publication.draftId] = publication
        return publication
    }

    @Synchronized
    override fun activateMediaPublication(post: Post, publication: PostPublication): Pair<Post, PostPublication> {
        posts[post.id] = post
        publications[publication.draftId] = publication
        return post to publication
    }

    override fun findPostPublication(draftId: String): PostPublication? = publications[draftId]

    override fun listPendingPostPublications(limit: Int): List<PostPublication> =
        publications.values
            .filter { it.state in setOf(PostPublicationState.PENDING_SOURCE, PostPublicationState.PROCESSING_MEDIA, PostPublicationState.PENDING_MEDIA, PostPublicationState.NEEDS_MEDIA_ACTION) }
            .sortedBy { it.requestedAt }
            .take(limit)

    override fun recordMediaLifecycleEvent(eventId: String): Boolean = mediaEventInbox.add(eventId)
    override fun mediaLifecycleCursor(): Long = mediaEventSequence
    override fun updateMediaLifecycleCursor(sequence: Long) { mediaEventSequence = maxOf(mediaEventSequence, sequence) }

    override fun listPostsByAuthor(authorId: String, limit: Int): List<Post> =
        listPostsByOwner(OwnerRef(OwnerType.USER, authorId), limit)

    override fun listPostsByOwner(owner: OwnerRef, limit: Int): List<Post> =
        posts.values
            .filter { it.ownerType == owner.ownerType && it.ownerId == owner.ownerId && it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listRecentPosts(limit: Int): List<Post> =
        posts.values
            .filter { it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listViewerTagAffinity(actor: OwnerRef, limit: Int): List<String> {
        val likedPostIds = postLikes.filter { it.second == actor }.map { it.first }.toSet()
        val viewedPostIds = postViews.values.filter { it.actor == actor }.map { it.postId }.toSet()
        return (likedPostIds + viewedPostIds)
            .asSequence()
            .mapNotNull(posts::get)
            .flatMap { it.tags.asSequence() }
            .filter(String::isNotBlank)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    @Synchronized
    override fun reserveRecommendationPlacement(
        viewer: OwnerRef,
        postId: String,
        constellationKey: String,
        constellationFactory: (List<RecommendationConstellation>) -> RecommendationConstellation,
        placementFactory: (RecommendationConstellation, List<RecommendationPlacement>) -> RecommendationPlacement
    ): RecommendationPlacement {
        val slotKey = RecommendationSlotKey(viewer, postId)
        recommendationSlots[slotKey]?.let { return it }
        val constellation = recommendationConstellations[viewer to constellationKey]
            ?: constellationFactory(
                recommendationConstellations
                    .asSequence()
                    .filter { it.key.first == viewer }
                    .map { it.value }
                    .sortedBy { it.key }
                    .toList()
            ).also { created ->
                require(created.key == constellationKey) { "Constellation key must match placement key" }
                recommendationConstellations[viewer to constellationKey] = created
            }
        val placement = placementFactory(
            constellation,
            recommendationSlots
                .asSequence()
                .filter { it.key.viewer == viewer }
                .map { it.value }
                .toList()
        )
        require(placement.constellationKey == constellationKey) { "Placement constellation key must match" }
        recommendationSlots[slotKey] = placement
        return placement
    }

    override fun listRecommendationConstellations(viewer: OwnerRef, keys: Set<String>): List<RecommendationConstellation> =
        recommendationConstellations
            .asSequence()
            .filter { it.key.first == viewer && (keys.isEmpty() || it.key.second in keys) }
            .map { it.value }
            .sortedBy { it.key }
            .toList()

    override fun setPostLike(postId: String, actor: OwnerRef, liked: Boolean) {
        val key = postId to actor
        if (liked) postLikes.add(key) else postLikes.remove(key)
    }

    override fun countPostLikes(postId: String): Long =
        postLikes.count { it.first == postId }.toLong()

    override fun isPostLikedBy(postId: String, actor: OwnerRef): Boolean =
        postLikes.contains(postId to actor)

    override fun recordPostView(postId: String, actor: OwnerRef, durationMs: Long, viewedAt: Instant) {
        postViews.compute(postId to actor) { _, current ->
            PostView(
                postId = postId,
                actor = actor,
                durationMs = (current?.durationMs ?: 0L) + durationMs.coerceAtLeast(0),
                viewedAt = viewedAt,
                viewCount = (current?.viewCount ?: 0L) + 1
            )
        }
    }

    override fun countPostViews(postId: String): Long =
        postViews.values.filter { it.postId == postId }.sumOf { it.viewCount }

    override fun countPostViewsByUser(postId: String, actor: OwnerRef): Long =
        postViews[postId to actor]?.viewCount ?: 0L

    override fun setPollVote(postId: String, blockId: String, actor: OwnerRef, optionId: String) {
        pollVotes[Triple(postId, blockId, actor)] = optionId
    }

    override fun pollVoteCounts(postId: String, blockId: String): Map<String, Long> =
        pollVotes.asSequence()
            .filter { it.key.first == postId && it.key.second == blockId }
            .groupingBy { it.value }
            .eachCount()
            .mapValues { it.value.toLong() }

    override fun pollVoteForActor(postId: String, blockId: String, actor: OwnerRef): String? =
        pollVotes[Triple(postId, blockId, actor)]

    override fun setStoryLike(storyId: String, actor: OwnerRef, liked: Boolean) {
        val key = storyId to actor
        if (liked) storyLikes.add(key) else storyLikes.remove(key)
    }

    override fun countStoryLikes(storyId: String): Long =
        storyLikes.count { it.first == storyId }.toLong()

    override fun isStoryLikedBy(storyId: String, actor: OwnerRef): Boolean =
        storyLikes.contains(storyId to actor)

    override fun setCommentLike(commentId: String, actor: OwnerRef, liked: Boolean) {
        val key = commentId to actor
        if (liked) commentLikes.add(key) else commentLikes.remove(key)
    }

    override fun countCommentLikes(commentId: String): Long =
        commentLikes.count { it.first == commentId }.toLong()

    override fun isCommentLikedBy(commentId: String, actor: OwnerRef): Boolean =
        commentLikes.contains(commentId to actor)

    override fun saveStory(story: Story): Story {
        stories[story.id] = story
        return story
    }

    override fun deleteStory(storyId: String) {
        stories.remove(storyId)
        storyLikes.removeIf { it.first == storyId }
        storyViews.keys.filter { it.startsWith("$storyId:") }.forEach(storyViews::remove)
        mediaReferences.removeIf { it.ownerType == "story" && it.ownerId == storyId }
    }

    override fun findStory(id: String): Story? =
        stories[id]?.takeIf { it.status != ContentStatus.DELETED }

    override fun findStoryByAssetId(assetId: String): Story? =
        stories.values.firstOrNull { story ->
            story.status != ContentStatus.DELETED && story.blocks.any { block ->
                (block.data["assetId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == assetId
            }
        }

    override fun listActiveStories(now: Instant, limit: Int): List<Story> =
        stories.values
            .filter { it.status == ContentStatus.ACTIVE && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story> =
        listActiveStoriesByOwner(OwnerRef(OwnerType.USER, authorId), now, limit)

    override fun listActiveStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int): List<Story> =
        stories.values
            .filter { it.ownerType == owner.ownerType && it.ownerId == owner.ownerId && it.status == ContentStatus.ACTIVE && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant?): List<Story> =
        listArchivedStoriesByOwner(OwnerRef(OwnerType.USER, authorId), now, limit, cursor)

    override fun listArchivedStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int, cursor: Instant?): List<Story> =
        stories.values
            .filter { story ->
                story.ownerType == owner.ownerType &&
                    story.ownerId == owner.ownerId &&
                    story.status != ContentStatus.DELETED &&
                    (story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now)) &&
                    (cursor == null || story.createdAt.isBefore(cursor))
            }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listArchivedStoryPeriods(owner: OwnerRef, now: Instant, limit: Int): List<StoryArchivePeriod> =
        stories.values
            .asSequence()
            .filter { story ->
                story.ownerType == owner.ownerType && story.ownerId == owner.ownerId &&
                    story.status != ContentStatus.DELETED &&
                    (story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now))
            }
            .groupBy { it.createdAt.toString().take(7) }
            .entries
            .sortedByDescending { it.key }
            .take(limit)
            .map { (period, storiesForPeriod) ->
                StoryArchivePeriod(period, storiesForPeriod.size, storiesForPeriod.maxByOrNull { it.createdAt }?.id)
            }
            .toList()

    override fun recordStoryView(storyId: String, actor: OwnerRef, viewedAt: Instant) {
        storyViews["$storyId:${actor.ownerType}:${actor.ownerId}"] = viewedAt
    }

    override fun isStoryViewed(storyId: String, actor: OwnerRef): Boolean =
        storyViews.containsKey("$storyId:${actor.ownerType}:${actor.ownerId}")

    override fun saveComment(comment: Comment): Comment {
        comments[comment.id] = comment
        return comment
    }

    override fun updateComment(comment: Comment): Comment {
        comments[comment.id] = comment
        return comment
    }

    override fun deleteComment(commentId: String) {
        comments.remove(commentId)
        comments.values.filter { it.parentId == commentId }.map { it.id }.forEach(::deleteComment)
        commentLikes.removeIf { it.first == commentId }
        commentViewerHides.removeIf { it.first == commentId }
        commentReports.removeIf { it.commentId == commentId }
        mediaReferences.removeIf { it.ownerType == "comment" && it.ownerId == commentId }
    }

    /**
     * Deleted comments intentionally remain addressable: a reply may point to
     * a tombstone and the thread must not lose its descendants.
     */
    override fun findComment(id: String): Comment? = comments[id]

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> =
        comments.values
            .filter { it.postId == postId && it.status != ContentStatus.HIDDEN }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun savePostSearchProjection(projection: PostSearchProjection): PostSearchProjection {
        postSearchProjections[projection.postId] = projection
        return projection
    }

    override fun findPostSearchProjection(postId: String): PostSearchProjection? = postSearchProjections[postId]

    override fun setPinnedComment(postId: String, commentId: String?, pinnedAt: Instant?) {
        synchronized(comments) {
            comments.values
                .filter { it.postId == postId && it.parentId == null && it.pinnedAt != null }
                .forEach { comment -> comments[comment.id] = comment.copy(pinnedAt = null) }
            if (commentId != null && pinnedAt != null) {
                val comment = comments[commentId] ?: return
                require(comment.postId == postId && comment.parentId == null) { "Only root comments can be pinned" }
                comments[commentId] = comment.copy(pinnedAt = pinnedAt)
            }
        }
    }

    override fun saveCommentReport(report: CommentReport) {
        commentReports.add(report)
    }

    override fun hideCommentForViewer(commentId: String, actor: OwnerRef) {
        commentViewerHides.add(commentId to actor)
    }

    override fun hiddenCommentIdsForViewer(postId: String, actor: OwnerRef): Set<String> =
        commentViewerHides.asSequence()
            .filter { it.second == actor && comments[it.first]?.postId == postId }
            .map { it.first }
            .toSet()

    override fun saveCollection(collection: SavedCollection): SavedCollection {
        require(collections.values.none {
            it.ownerType == collection.ownerType &&
                it.ownerId == collection.ownerId &&
                it.title.equals(collection.title, ignoreCase = true)
        }) { "Collection title already exists" }
        collections[collection.id] = collection
        return collection
    }

    override fun updateCollection(collection: SavedCollection): SavedCollection {
        collections[collection.id] = collection
        return collection
    }

    override fun deleteCollection(collectionId: String) {
        collections.remove(collectionId)
        collectionItems.keys.filter { it.first == collectionId }.forEach(collectionItems::remove)
    }

    override fun findCollection(id: String): SavedCollection? =
        collections[id]?.let(::withCollectionStats)

    override fun listCollectionsByOwner(owner: OwnerRef, limit: Int): List<SavedCollection> =
        collections.values
            .filter { it.ownerType == owner.ownerType && it.ownerId == owner.ownerId }
            .map(::withCollectionStats)
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override fun listCollectionPosts(collectionId: String, limit: Int): List<Post> =
        collectionItems.values
            .filter { it.collectionId == collectionId }
            .sortedByDescending { it.addedAt }
            .mapNotNull { posts[it.postId] }
            .filter { it.status == ContentStatus.ACTIVE }
            .take(limit)

    override fun listPostCollectionIds(owner: OwnerRef, postId: String): List<String> =
        collectionItems.values
            .filter { it.postId == postId }
            .mapNotNull { item -> collections[item.collectionId]?.takeIf { it.ownerType == owner.ownerType && it.ownerId == owner.ownerId }?.id }
            .distinct()

    override fun addPostToCollection(collectionId: String, postId: String, addedAt: Instant) {
        collectionItems[collectionId to postId] = CollectionItem(collectionId, postId, addedAt)
        collections.computeIfPresent(collectionId) { _, current -> current.copy(updatedAt = addedAt) }
    }

    override fun removePostFromCollection(collectionId: String, postId: String) {
        collectionItems.remove(collectionId to postId)
    }

    override fun saveMediaReference(reference: ContentMediaReference) {
        mediaReferences.add(reference)
    }

    override fun listMediaReferences(blobId: String): List<ContentMediaReference> =
        mediaReferences.filter { it.blobId == blobId }

    override fun findLegacyMediaReferences(blobId: String): List<ContentMediaReference> =
        posts.values
            .filter { post -> post.blocks.any { it.hasBlobId(blobId) } }
            .map { post -> ContentMediaReference("post", post.id, blobId, createdAt = post.createdAt) } +
            comments.values
                .filter { comment -> comment.blocks.any { it.hasBlobId(blobId) } }
                .map { comment -> ContentMediaReference("comment", comment.id, blobId, createdAt = comment.createdAt) } +
            stories.values
                .filter { story -> story.blocks.any { it.hasBlobId(blobId) } }
                .map { story -> ContentMediaReference("story", story.id, blobId, createdAt = story.createdAt) }

    private fun withCollectionStats(collection: SavedCollection): SavedCollection {
        val items = collectionItems.values
            .filter { it.collectionId == collection.id }
            .sortedByDescending { it.addedAt }
        return collection.copy(
            itemCount = items.size,
            previewBlocks = items
                .mapNotNull { posts[it.postId] }
                .flatMap { post -> post.blocks.filter { it.type == ContentBlockType.IMAGE || it.type == ContentBlockType.VIDEO } }
                .take(3)
        )
    }

    private fun com.onix.content.domain.ContentBlock.hasBlobId(blobId: String): Boolean =
        data["blobId"]?.jsonPrimitive?.contentOrNull == blobId
}
