package com.onix.content.service

import com.onix.content.domain.Comment
import com.onix.content.domain.ContentBlockType
import com.onix.content.domain.ContentMediaReference
import com.onix.content.domain.ContentStatus
import com.onix.content.domain.OwnerRef
import com.onix.content.domain.OwnerType
import com.onix.content.domain.Post
import com.onix.content.domain.SavedCollection
import com.onix.content.domain.Story
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

    private val posts = ConcurrentHashMap<String, Post>()
    private val stories = ConcurrentHashMap<String, Story>()
    private val comments = ConcurrentHashMap<String, Comment>()
    private val collections = ConcurrentHashMap<String, SavedCollection>()
    private val collectionItems = ConcurrentHashMap<Pair<String, String>, CollectionItem>()
    private val mediaReferences = ConcurrentHashMap.newKeySet<ContentMediaReference>()
    private val postLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val storyLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val commentLikes = ConcurrentHashMap.newKeySet<Pair<String, OwnerRef>>()
    private val postViews = ConcurrentHashMap<Pair<String, OwnerRef>, PostView>()
    private val storyViews = ConcurrentHashMap<String, Instant>()

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
        comments.values.filter { it.postId == postId }.map { it.id }.forEach(::deleteComment)
        collectionItems.keys.filter { it.second == postId }.forEach(collectionItems::remove)
        postLikes.removeIf { it.first == postId }
        postViews.keys.filter { it.first == postId }.forEach(postViews::remove)
        mediaReferences.removeIf { it.ownerType == "post" && it.ownerId == postId }
    }

    override fun findPost(id: String): Post? = posts[id]?.takeIf { it.status == ContentStatus.ACTIVE }

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
        mediaReferences.removeIf { it.ownerType == "comment" && it.ownerId == commentId }
    }

    override fun findComment(id: String): Comment? = comments[id]?.takeIf { it.status == ContentStatus.ACTIVE }

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> =
        comments.values
            .filter { it.postId == postId && it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)

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
