package com.onix.content.service

import com.onix.content.domain.*
import java.time.Instant

interface ContentRepository {
    fun savePost(post: Post): Post
    fun updatePost(post: Post): Post
    fun deletePost(postId: String)
    fun findPost(id: String): Post?
    fun findStoredPost(id: String): Post?
    fun findStoredPostByAssetId(assetId: String): Post?
    fun listDraftPosts(owner: OwnerRef, limit: Int): List<Post>
    fun savePostEditorDocument(document: PostEditorDocument): PostEditorDocument
    fun findPostEditorDocument(revisionId: String): PostEditorDocument?
    fun findWorkingPostEditorDocument(postId: String): PostEditorDocument?
    fun updatePostEditorRevisionState(revisionId: String, state: PostRevisionState): PostEditorDocument?
    fun savePostPublication(publication: PostPublication): PostPublication
    fun activateMediaPublication(post: Post, publication: PostPublication): Pair<Post, PostPublication>
    fun findPostPublication(draftId: String): PostPublication?
    fun listPendingPostPublications(limit: Int): List<PostPublication>
    /** Returns false for an already-consumed Media outbox event. */
    fun recordMediaLifecycleEvent(eventId: String): Boolean
    fun mediaLifecycleCursor(): Long
    fun updateMediaLifecycleCursor(sequence: Long)
    fun listPostsByAuthor(authorId: String, limit: Int): List<Post>
    fun listPostsByOwner(owner: OwnerRef, limit: Int): List<Post>
    fun listRecentPosts(limit: Int): List<Post>
    fun listViewerTagAffinity(actor: OwnerRef, limit: Int): List<String>
    /**
     * Atomically returns an existing persistent scene slot or creates one while
     * holding the viewer's placement lock.  The callbacks are pure coordinate
     * calculators; persistence remains owned by the repository.
     */
    fun reserveRecommendationPlacement(
        viewer: OwnerRef,
        postId: String,
        constellationKey: String,
        constellationFactory: (List<RecommendationConstellation>) -> RecommendationConstellation,
        placementFactory: (RecommendationConstellation, List<RecommendationPlacement>) -> RecommendationPlacement
    ): RecommendationPlacement
    fun listRecommendationConstellations(viewer: OwnerRef, keys: Set<String> = emptySet()): List<RecommendationConstellation>
    fun setPostLike(postId: String, actor: OwnerRef, liked: Boolean)
    fun countPostLikes(postId: String): Long
    fun isPostLikedBy(postId: String, actor: OwnerRef): Boolean
    fun recordPostView(postId: String, actor: OwnerRef, durationMs: Long, viewedAt: Instant)
    fun countPostViews(postId: String): Long
    fun countPostViewsByUser(postId: String, actor: OwnerRef): Long
    fun setPollVote(postId: String, blockId: String, actor: OwnerRef, optionId: String)
    fun pollVoteCounts(postId: String, blockId: String): Map<String, Long>
    fun pollVoteForActor(postId: String, blockId: String, actor: OwnerRef): String?
    fun setStoryLike(storyId: String, actor: OwnerRef, liked: Boolean)
    fun countStoryLikes(storyId: String): Long
    fun isStoryLikedBy(storyId: String, actor: OwnerRef): Boolean
    fun setCommentLike(commentId: String, actor: OwnerRef, liked: Boolean)
    fun countCommentLikes(commentId: String): Long
    fun isCommentLikedBy(commentId: String, actor: OwnerRef): Boolean
    fun saveStory(story: Story): Story
    fun deleteStory(storyId: String)
    fun findStory(id: String): Story?
    fun findStoryByAssetId(assetId: String): Story?
    fun listActiveStories(now: Instant, limit: Int): List<Story>
    fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story>
    fun listActiveStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int): List<Story>
    fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant? = null): List<Story>
    fun listArchivedStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int, cursor: Instant? = null): List<Story>
    fun listArchivedStoryPeriods(owner: OwnerRef, now: Instant, limit: Int): List<StoryArchivePeriod>
    fun recordStoryView(storyId: String, actor: OwnerRef, viewedAt: Instant)
    fun isStoryViewed(storyId: String, actor: OwnerRef): Boolean
    fun saveComment(comment: Comment): Comment
    fun updateComment(comment: Comment): Comment
    fun deleteComment(commentId: String)
    fun findComment(id: String): Comment?
    fun listCommentsForPost(postId: String, limit: Int): List<Comment>
    /** Atomically clears the former pin and optionally pins one root comment. */
    fun setPinnedComment(postId: String, commentId: String?, pinnedAt: Instant?)
    fun saveCommentReport(report: CommentReport)
    fun hideCommentForViewer(commentId: String, actor: OwnerRef)
    fun hiddenCommentIdsForViewer(postId: String, actor: OwnerRef): Set<String>
    fun savePostSearchProjection(projection: PostSearchProjection): PostSearchProjection
    fun findPostSearchProjection(postId: String): PostSearchProjection?
    fun saveCollection(collection: SavedCollection): SavedCollection
    fun updateCollection(collection: SavedCollection): SavedCollection
    fun deleteCollection(collectionId: String)
    fun findCollection(id: String): SavedCollection?
    fun listCollectionsByOwner(owner: OwnerRef, limit: Int): List<SavedCollection>
    fun listCollectionPosts(collectionId: String, limit: Int): List<Post>
    fun listPostCollectionIds(owner: OwnerRef, postId: String): List<String>
    fun addPostToCollection(collectionId: String, postId: String, addedAt: Instant)
    fun removePostFromCollection(collectionId: String, postId: String)
    fun saveMediaReference(reference: ContentMediaReference)
    fun listMediaReferences(blobId: String): List<ContentMediaReference>
    fun findLegacyMediaReferences(blobId: String): List<ContentMediaReference>
}
