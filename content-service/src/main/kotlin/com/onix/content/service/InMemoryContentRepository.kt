package com.onix.content.service

import com.onix.content.domain.Comment
import com.onix.content.domain.ContentStatus
import com.onix.content.domain.Post
import com.onix.content.domain.Story
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryContentRepository : ContentRepository {
    private data class PostView(
        val postId: String,
        val userId: String,
        val durationMs: Long,
        val viewedAt: Instant,
        val viewCount: Long
    )

    private val posts = ConcurrentHashMap<String, Post>()
    private val stories = ConcurrentHashMap<String, Story>()
    private val comments = ConcurrentHashMap<String, Comment>()
    private val postLikes = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val storyLikes = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val commentLikes = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val postViews = ConcurrentHashMap<Pair<String, String>, PostView>()
    private val storyViews = ConcurrentHashMap<String, Instant>()

    override fun savePost(post: Post): Post {
        posts[post.id] = post
        return post
    }

    override fun findPost(id: String): Post? = posts[id]?.takeIf { it.status == ContentStatus.ACTIVE }

    override fun listPostsByAuthor(authorId: String, limit: Int): List<Post> =
        posts.values
            .filter { it.authorId == authorId && it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listRecentPosts(limit: Int): List<Post> =
        posts.values
            .filter { it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listViewerTagAffinity(userId: String, limit: Int): List<String> {
        val likedPostIds = postLikes.filter { it.second == userId }.map { it.first }.toSet()
        val viewedPostIds = postViews.values.filter { it.userId == userId }.map { it.postId }.toSet()
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

    override fun setPostLike(postId: String, userId: String, liked: Boolean) {
        val key = postId to userId
        if (liked) postLikes.add(key) else postLikes.remove(key)
    }

    override fun countPostLikes(postId: String): Long =
        postLikes.count { it.first == postId }.toLong()

    override fun isPostLikedBy(postId: String, userId: String): Boolean =
        postLikes.contains(postId to userId)

    override fun recordPostView(postId: String, userId: String, durationMs: Long, viewedAt: Instant) {
        postViews.compute(postId to userId) { _, current ->
            PostView(
                postId = postId,
                userId = userId,
                durationMs = (current?.durationMs ?: 0L) + durationMs.coerceAtLeast(0),
                viewedAt = viewedAt,
                viewCount = (current?.viewCount ?: 0L) + 1
            )
        }
    }

    override fun countPostViews(postId: String): Long =
        postViews.values.filter { it.postId == postId }.sumOf { it.viewCount }

    override fun countPostViewsByUser(postId: String, userId: String): Long =
        postViews[postId to userId]?.viewCount ?: 0L

    override fun setStoryLike(storyId: String, userId: String, liked: Boolean) {
        val key = storyId to userId
        if (liked) storyLikes.add(key) else storyLikes.remove(key)
    }

    override fun countStoryLikes(storyId: String): Long =
        storyLikes.count { it.first == storyId }.toLong()

    override fun isStoryLikedBy(storyId: String, userId: String): Boolean =
        storyLikes.contains(storyId to userId)

    override fun setCommentLike(commentId: String, userId: String, liked: Boolean) {
        val key = commentId to userId
        if (liked) commentLikes.add(key) else commentLikes.remove(key)
    }

    override fun countCommentLikes(commentId: String): Long =
        commentLikes.count { it.first == commentId }.toLong()

    override fun isCommentLikedBy(commentId: String, userId: String): Boolean =
        commentLikes.contains(commentId to userId)

    override fun saveStory(story: Story): Story {
        stories[story.id] = story
        return story
    }

    override fun findStory(id: String): Story? =
        stories[id]?.takeIf { it.status != ContentStatus.DELETED }

    override fun listActiveStories(now: Instant, limit: Int): List<Story> =
        stories.values
            .filter { it.status == ContentStatus.ACTIVE && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story> =
        stories.values
            .filter { it.authorId == authorId && it.status == ContentStatus.ACTIVE && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant?): List<Story> =
        stories.values
            .filter { story ->
                story.authorId == authorId &&
                    story.status != ContentStatus.DELETED &&
                    (story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now)) &&
                    (cursor == null || story.createdAt.isBefore(cursor))
            }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun recordStoryView(storyId: String, userId: String, viewedAt: Instant) {
        storyViews["$storyId:$userId"] = viewedAt
    }

    override fun isStoryViewed(storyId: String, userId: String): Boolean =
        storyViews.containsKey("$storyId:$userId")

    override fun saveComment(comment: Comment): Comment {
        comments[comment.id] = comment
        return comment
    }

    override fun findComment(id: String): Comment? = comments[id]?.takeIf { it.status == ContentStatus.ACTIVE }

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> =
        comments.values
            .filter { it.postId == postId && it.status == ContentStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
            .take(limit)
}
