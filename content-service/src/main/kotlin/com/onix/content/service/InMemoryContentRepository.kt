package com.onix.content.service

import com.onix.content.domain.Comment
import com.onix.content.domain.ContentStatus
import com.onix.content.domain.Post
import com.onix.content.domain.Story
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryContentRepository : ContentRepository {
    private val posts = ConcurrentHashMap<String, Post>()
    private val stories = ConcurrentHashMap<String, Story>()
    private val comments = ConcurrentHashMap<String, Comment>()

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

    override fun saveStory(story: Story): Story {
        stories[story.id] = story
        return story
    }

    override fun findStory(id: String): Story? =
        stories[id]?.takeIf { it.status == ContentStatus.ACTIVE && it.expiresAt.isAfter(Instant.now()) }

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
