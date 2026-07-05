package com.onix.content.service

import com.onix.content.domain.Comment
import com.onix.content.domain.Post
import com.onix.content.domain.Story
import java.time.Instant

interface ContentRepository {
    fun savePost(post: Post): Post
    fun findPost(id: String): Post?
    fun listPostsByAuthor(authorId: String, limit: Int): List<Post>
    fun listRecentPosts(limit: Int): List<Post>
    fun setPostLike(postId: String, userId: String, liked: Boolean)
    fun countPostLikes(postId: String): Long
    fun isPostLikedBy(postId: String, userId: String): Boolean
    fun setStoryLike(storyId: String, userId: String, liked: Boolean)
    fun countStoryLikes(storyId: String): Long
    fun isStoryLikedBy(storyId: String, userId: String): Boolean
    fun saveStory(story: Story): Story
    fun findStory(id: String): Story?
    fun listActiveStories(now: Instant, limit: Int): List<Story>
    fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story>
    fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant? = null): List<Story>
    fun recordStoryView(storyId: String, userId: String, viewedAt: Instant)
    fun isStoryViewed(storyId: String, userId: String): Boolean
    fun saveComment(comment: Comment): Comment
    fun findComment(id: String): Comment?
    fun listCommentsForPost(postId: String, limit: Int): List<Comment>
}
