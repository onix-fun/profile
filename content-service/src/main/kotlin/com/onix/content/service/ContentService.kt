package com.onix.content.service

import com.onix.content.domain.*
import com.onix.content.search.SearchEventPublisher
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ContentService(
    private val repository: ContentRepository,
    private val searchEvents: SearchEventPublisher = SearchEventPublisher.noop(),
    private val clock: Clock = Clock.systemUTC()
) {
    fun createPost(author: SessionUser, input: CreatePostInput): Post {
        val normalizedTags = input.tags.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().take(20)
        val blocks = input.blocks.ifEmpty { if (input.text.isBlank()) emptyList() else listOf(textBlock(input.text)) }
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        require(text.isNotBlank() || blocks.isNotEmpty()) { "Post must contain text or media" }
        val now = Instant.now(clock)
        val post = repository.savePost(
            Post(
                id = UUID.randomUUID().toString(),
                authorId = author.id,
                title = input.title?.trim()?.takeIf(String::isNotBlank),
                text = text,
                blocks = blocks,
                tags = normalizedTags,
                visibility = input.visibility,
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.postUpsert(post)
        return post
    }

    fun createStory(author: SessionUser, input: CreateStoryInput): Story {
        require(input.blocks.isNotEmpty()) { "Story must contain at least one block" }
        val now = Instant.now(clock)
        return repository.saveStory(
            Story(
                id = UUID.randomUUID().toString(),
                authorId = author.id,
                blocks = input.blocks,
                visibility = input.visibility,
                createdAt = now,
                expiresAt = now.plusSeconds(24 * 60 * 60)
            )
        )
    }

    fun createComment(author: SessionUser, input: CreateCommentInput): Comment {
        require(input.text.isNotBlank()) { "Comment text is required" }
        repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        if (input.parentId != null) {
            val parent = repository.findComment(input.parentId) ?: throw IllegalArgumentException("Parent comment not found")
            require(parent.parentId == null) { "Replies cannot have nested replies" }
        }
        val now = Instant.now(clock)
        val comment = repository.saveComment(
            Comment(
                id = UUID.randomUUID().toString(),
                postId = input.postId,
                authorId = author.id,
                parentId = input.parentId,
                text = input.text.trim(),
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.commentUpsert(comment)
        return comment
    }

    fun profileContent(ownerId: String, visibility: AccountVisibility, postLimit: Int, storyLimit: Int): ProfileContentResponse {
        if (!visibility.canSeePrivateContent) {
            return ProfileContentResponse()
        }
        val now = Instant.now(clock)
        val posts = repository.listPostsByAuthor(ownerId, postLimit.coerceIn(1, 500))
            .map { withViewerState(it, visibility.viewerId) }
        val stories = repository.listActiveStoriesByAuthor(ownerId, now, storyLimit.coerceIn(1, 50))
            .filter { canViewStory(it, visibility) }
        val comments = posts.flatMap { repository.listCommentsForPost(it.id, 3) }
        return ProfileContentResponse(posts = posts, stories = stories, comments = comments)
    }

    fun feed(viewerId: String, tagAffinity: Set<String>, limit: Int): List<FeedItem> {
        return repository.listRecentPosts(limit.coerceIn(1, 100) * 3)
            .map { withViewerState(it, viewerId) }
            .map { post ->
                val tagScore = post.tags.count { it in tagAffinity } * 4.0
                val likeScore = post.likeCount.coerceAtMost(30) * 0.15
                val ageHours = java.time.Duration.between(post.createdAt, Instant.now(clock)).toHours().coerceAtLeast(0)
                val recencyScore = 1.0 / (1 + ageHours).toDouble()
                val ownPenalty = if (post.authorId == viewerId) -2.0 else 0.0
                FeedItem(
                    post = post,
                    score = tagScore + likeScore + recencyScore + ownPenalty,
                    reasons = buildList {
                        if (tagScore > 0) add("tag-affinity")
                        if (post.likeCount > 0) add("liked")
                        add("recent")
                    }
                )
            }
            .sortedWith(compareByDescending<FeedItem> { it.score }.thenByDescending { it.post.createdAt })
            .take(limit.coerceIn(1, 100))
    }

    fun post(id: String, viewerId: String? = null): Post? =
        repository.findPost(id)?.let { withViewerState(it, viewerId) }

    fun likePost(user: SessionUser, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, user.id, true)
        return PostReactionState(postId = postId, liked = true, likeCount = repository.countPostLikes(postId))
    }

    fun unlikePost(user: SessionUser, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, user.id, false)
        return PostReactionState(postId = postId, liked = false, likeCount = repository.countPostLikes(postId))
    }

    fun story(id: String): Story? = repository.findStory(id)

    fun comments(postId: String, limit: Int): List<Comment> =
        repository.listCommentsForPost(postId, limit.coerceIn(1, 100))

    fun storiesFeed(viewerId: String, limit: Int): List<StoryRailItem> {
        val now = Instant.now(clock)
        return repository.listActiveStories(now, limit.coerceIn(1, 100))
            .groupBy { it.authorId }
            .values
            .map { stories ->
                val latest = stories.maxBy { it.createdAt }
                StoryRailItem(
                    authorId = latest.authorId,
                    authorName = if (latest.authorId == viewerId) "You" else "@${latest.authorId.take(8)}",
                    storyIds = stories.sortedByDescending { it.createdAt }.map { it.id },
                    activeCount = stories.size,
                    closeFriends = stories.any { it.visibility == Visibility.CLOSE_FRIENDS },
                    latestAt = latest.createdAt
                )
            }
            .sortedByDescending { it.latestAt }
            .take(limit.coerceIn(1, 100))
    }

    private fun canViewStory(story: Story, visibility: AccountVisibility): Boolean {
        if (visibility.isBlocked) return false
        if (story.authorId == visibility.viewerId) return true
        if (visibility.ownerId != story.authorId) return false
        return when (story.visibility) {
            Visibility.PUBLIC -> visibility.canSeePrivateContent
            Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
        }
    }

    private fun withViewerState(post: Post, viewerId: String?): Post =
        post.copy(
            likeCount = repository.countPostLikes(post.id),
            likedByViewer = viewerId?.let { repository.isPostLikedBy(post.id, it) } ?: false
        )
}
