package com.onix.content.service

import com.onix.content.domain.*
import java.time.Instant

interface ContentRepository {
    fun savePost(post: Post): Post
    fun findPost(id: String): Post?
    fun listPostsByAuthor(authorId: String, limit: Int): List<Post>
    fun listPostsByOwner(owner: OwnerRef, limit: Int): List<Post>
    fun listRecentPosts(limit: Int): List<Post>
    fun listViewerTagAffinity(actor: OwnerRef, limit: Int): List<String>
    fun setPostLike(postId: String, actor: OwnerRef, liked: Boolean)
    fun countPostLikes(postId: String): Long
    fun isPostLikedBy(postId: String, actor: OwnerRef): Boolean
    fun recordPostView(postId: String, actor: OwnerRef, durationMs: Long, viewedAt: Instant)
    fun countPostViews(postId: String): Long
    fun countPostViewsByUser(postId: String, actor: OwnerRef): Long
    fun setStoryLike(storyId: String, actor: OwnerRef, liked: Boolean)
    fun countStoryLikes(storyId: String): Long
    fun isStoryLikedBy(storyId: String, actor: OwnerRef): Boolean
    fun setCommentLike(commentId: String, actor: OwnerRef, liked: Boolean)
    fun countCommentLikes(commentId: String): Long
    fun isCommentLikedBy(commentId: String, actor: OwnerRef): Boolean
    fun saveStory(story: Story): Story
    fun findStory(id: String): Story?
    fun listActiveStories(now: Instant, limit: Int): List<Story>
    fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story>
    fun listActiveStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int): List<Story>
    fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant? = null): List<Story>
    fun listArchivedStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int, cursor: Instant? = null): List<Story>
    fun recordStoryView(storyId: String, actor: OwnerRef, viewedAt: Instant)
    fun isStoryViewed(storyId: String, actor: OwnerRef): Boolean
    fun saveComment(comment: Comment): Comment
    fun findComment(id: String): Comment?
    fun listCommentsForPost(postId: String, limit: Int): List<Comment>
    fun saveCollection(collection: SavedCollection): SavedCollection
    fun updateCollection(collection: SavedCollection): SavedCollection
    fun deleteCollection(collectionId: String)
    fun findCollection(id: String): SavedCollection?
    fun listCollectionsByOwner(owner: OwnerRef, limit: Int): List<SavedCollection>
    fun listCollectionPosts(collectionId: String, limit: Int): List<Post>
    fun listPostCollectionIds(owner: OwnerRef, postId: String): List<String>
    fun addPostToCollection(collectionId: String, postId: String, addedAt: Instant)
    fun removePostFromCollection(collectionId: String, postId: String)
}
