package com.onix.content.service

import com.onix.content.domain.*
import com.onix.content.media.MediaLifecycleEvent
import com.onix.content.profile.ProfileUsageReporter
import com.onix.content.search.SearchIndexClient
import com.onix.content.search.SearchEventPublisher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.Instant
import java.net.URI
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class ContentService(
    private val repository: ContentRepository,
    private val searchEvents: SearchEventPublisher = SearchEventPublisher.noop(),
    private val searchIndex: SearchIndexClient = SearchIndexClient.noop(),
    private val profileUsage: ProfileUsageReporter = ProfileUsageReporter.noop(),
    private val uploadedAssetVerifier: UploadedAssetVerifier = UploadedAssetVerifier.permissive(),
    private val mediaAssetProcessor: MediaAssetProcessor = MediaAssetProcessor.noop(),
    private val clock: Clock = Clock.systemUTC()
) {
    private data class ScoredRecommendation(
        val post: Post,
        val score: Double,
        val reasons: List<String>,
        val emphasis: FeedEmphasis
    )

    fun createPost(author: SessionUser, input: CreatePostInput): Post =
        createPost(CurrentActor(author, author.asAccountUser()), input)

    fun createPost(actor: CurrentActor, input: CreatePostInput): Post {
        if (input.assets != null) return createMediaPost(actor, input)
        val normalizedTags = normalizeTags(input.tags)
        val blocks = normalizeContentBlocks(input.blocks.ifEmpty { if (input.text.isBlank()) emptyList() else listOf(textBlock(input.text)) })
        validateContentBlocks(blocks)
        require(hasPublishableContent(input.text, blocks)) { "Post must contain publishable content" }
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        val now = Instant.now(clock)
        val post = repository.savePost(
            Post(
                id = UUID.randomUUID().toString(),
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                title = input.title?.trim()?.takeIf(String::isNotBlank),
                text = text,
                blocks = blocks,
                contentVersion = input.contentVersion.coerceAtLeast(1),
                tags = normalizedTags,
                allowComments = input.allowComments,
                visibility = input.visibility,
                createdAt = now,
                updatedAt = now
            )
        )
        indexPost(post)
        profileUsage.report(actor.activeOwner.ref(), "content", "posts")
        return withStableAssetUrls(post)
    }

    fun updatePost(actor: CurrentActor, input: UpdatePostInput): Post {
        val current = repository.findPost(input.id) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        if (input.assets != null || current.contentVersion >= MEDIA_POST_CONTENT_VERSION) {
            return updateMediaPost(actor, current, input)
        }
        val blocks = normalizeContentBlocks(input.blocks ?: current.blocks)
        val textSource = input.text ?: current.text
        validateContentBlocks(blocks)
        require(hasPublishableContent(textSource, blocks)) { "Post must contain publishable content" }
        val text = textSource.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        val next = current.copy(
            title = if (input.title != null) input.title.trim().takeIf(String::isNotBlank) else current.title,
            text = text,
            blocks = blocks.ifEmpty { if (text.isBlank()) emptyList() else listOf(textBlock(text)) },
            tags = input.tags?.let(::normalizeTags) ?: current.tags,
            allowComments = input.allowComments ?: current.allowComments,
            visibility = input.visibility ?: current.visibility,
            contentVersion = input.contentVersion?.coerceAtLeast(1) ?: current.contentVersion,
            updatedAt = Instant.now(clock)
        )
        val saved = repository.updatePost(next)
        indexPost(saved)
        return withStableAssetUrls(saved)
    }

    fun savePostDraft(actor: CurrentActor, input: SavePostDraftInput): Post {
        val now = Instant.now(clock)
        val current = input.id?.let { repository.findStoredPost(it) }
        if (input.id != null) {
            require(current != null) { "Draft not found" }
            require(current.status == ContentStatus.DRAFT) { "Only drafts can be autosaved" }
            requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        }
        if (input.assets != null || current?.contentVersion?.let { it >= MEDIA_POST_CONTENT_VERSION } == true) {
            val saved = saveMediaPostDraft(actor, input, current, now)
            if (current != null && mediaDraftDefinitionChanged(current, saved)) invalidatePendingPublication(saved.id)
            return withStableAssetUrls(saved)
        }
        val blocks = normalizeContentBlocks(input.blocks)
        validateContentBlocks(blocks)
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        val next = current?.copy(
            title = input.title?.trim()?.takeIf(String::isNotBlank),
            text = text,
            blocks = blocks,
            tags = normalizeTags(input.tags),
            allowComments = input.allowComments,
            contentVersion = input.contentVersion.coerceAtLeast(1),
            updatedAt = now
        ) ?: Post(
            id = UUID.randomUUID().toString(),
            authorId = actor.activeOwner.id,
            ownerType = actor.activeOwner.ownerType,
            ownerId = actor.activeOwner.id,
            author = actor.activeOwner,
            title = input.title?.trim()?.takeIf(String::isNotBlank),
            text = text,
            blocks = blocks,
            tags = normalizeTags(input.tags),
            allowComments = input.allowComments,
            visibility = Visibility.PUBLIC,
            status = ContentStatus.DRAFT,
            contentVersion = input.contentVersion.coerceAtLeast(1),
            createdAt = now,
            updatedAt = now
        )
        return (if (current == null) repository.savePost(next) else repository.updatePost(next)).also {
            invalidatePendingPublication(it.id)
        }
    }

    fun listPostDrafts(actor: CurrentActor, limit: Int = 40): List<Post> =
        repository.listDraftPosts(actor.activeOwner.ref(), limit.coerceIn(1, 100)).map(::withStableAssetUrls)

    fun postDraft(actor: CurrentActor, draftId: String): Post {
        val draft = repository.findStoredPost(draftId) ?: throw IllegalArgumentException("Draft not found")
        require(draft.status == ContentStatus.DRAFT) { "Post is not a draft" }
        requireOwnsContent(actor.activeOwner.ref(), draft.ownerRef())
        return withStableAssetUrls(draft)
    }

    fun createPostDraft(actor: CurrentActor): PostEditorDocument {
        val now = Instant.now(clock)
        val post = repository.savePost(Post(
            id = UUID.randomUUID().toString(), authorId = actor.activeOwner.id,
            ownerType = actor.activeOwner.ownerType, ownerId = actor.activeOwner.id, author = actor.activeOwner,
            text = "", blocks = emptyList(), tags = emptyList(), assets = emptyList(),
            allowComments = true, visibility = Visibility.PUBLIC, status = ContentStatus.DRAFT,
            contentVersion = MEDIA_POST_CONTENT_VERSION, createdAt = now, updatedAt = now
        ))
        return repository.savePostEditorDocument(PostEditorDocument(
            revisionId = UUID.randomUUID().toString(), postId = post.id, revisionNo = 1,
            editVersion = 1, state = PostRevisionState.DRAFT, assets = emptyList(), tags = emptyList(),
            allowComments = true, updatedAt = now
        ))
    }

    fun beginPostEdit(actor: CurrentActor, postId: String): PostEditorDocument {
        val post = repository.findStoredPost(postId) ?: throw IllegalArgumentException("Post not found")
        require(post.status == ContentStatus.ACTIVE) { "Only an active project can start a publication revision" }
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        val working = repository.findWorkingPostEditorDocument(postId)
        if (working?.state == PostRevisionState.DRAFT) return withEditorAssetUrls(working)
        if (working != null) {
            repository.updatePostEditorRevisionState(working.revisionId, PostRevisionState.SUPERSEDED)
            repository.findPostPublication(postId)?.takeIf { it.revisionId == working.revisionId }?.let { pending ->
                pending.processingRunIds.values.forEach { runId -> runCatching { mediaAssetProcessor.cancel(post.ownerRef().key(), runId) } }
                repository.savePostPublication(pending.copy(state = PostPublicationState.CANCELLED))
            }
        }
        val baseAssets = working?.assets ?: post.assets
        val canonical = repairProjectLayout(baseAssets.map { item ->
            val verified = item.assetId?.let { uploadedAssetVerifier.asset(post.ownerRef().key(), it) }
            verified?.copy(id = item.id, layout = item.layout) ?: item
        })
        val nextRevision = maxOf(working?.revisionNo ?: 0, repository.findPostPublication(postId)?.revision ?: 0) + 1
        return repository.savePostEditorDocument(PostEditorDocument(
            revisionId = UUID.randomUUID().toString(), postId = postId, revisionNo = nextRevision,
            editVersion = 1, state = PostRevisionState.DRAFT, assets = canonical,
            tags = working?.tags ?: post.tags, allowComments = working?.allowComments ?: post.allowComments, updatedAt = Instant.now(clock)
        )).let(::withEditorAssetUrls)
    }

    fun postEditorDocument(actor: CurrentActor, revisionId: String): PostEditorDocument {
        val document = repository.findPostEditorDocument(revisionId) ?: throw IllegalArgumentException("Editor revision not found")
        val post = repository.findStoredPost(document.postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        return withEditorAssetUrls(document)
    }

    fun editorMediaAssets(actor: CurrentActor, assetIds: List<String>): List<EditorMediaAssetResult> {
        val ids = assetIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(ids.size <= MAX_MEDIA_ASSETS) { "At most $MAX_MEDIA_ASSETS media assets can be refreshed" }
        val resolved = uploadedAssetVerifier.assets(actor.activeOwner.ref().key(), ids)
        return ids.map { id ->
            resolved[id]?.let { EditorMediaAssetResult(assetId = id, asset = it) }
                ?: EditorMediaAssetResult(assetId = id, failureCode = "MEDIA_UNAVAILABLE")
        }
    }

    fun savePostEditorDocument(actor: CurrentActor, input: SavePostEditorDocumentInput): PostEditorDocument {
        val current = repository.findPostEditorDocument(input.revisionId) ?: throw IllegalArgumentException("Editor revision not found")
        require(current.state == PostRevisionState.DRAFT) { "Publication revision is immutable after publication is requested" }
        val post = repository.findStoredPost(current.postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        val normalized = normalizeMediaAssets(input.assets, post.ownerRef())
        val adjustments = normalized.zip(input.assets).mapNotNull { (after, before) ->
            after.id.takeIf { after.layout != before.layout }
        }
        return repository.savePostEditorDocument(current.copy(
            editVersion = maxOf(current.editVersion, input.editVersion) + 1,
            assets = normalized, tags = normalizeMediaTags(input.tags, required = false),
            allowComments = input.allowComments, layoutAdjustments = adjustments, updatedAt = Instant.now(clock)
        )).let(::withEditorAssetUrls)
    }

    fun requestPostRevisionPublication(actor: CurrentActor, revisionId: String, idempotencyKey: String): PostPublication {
        val document = repository.findPostEditorDocument(revisionId) ?: throw IllegalArgumentException("Editor revision not found")
        val post = repository.findStoredPost(document.postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        require(document.state == PostRevisionState.DRAFT) { "Publication revision is already frozen" }
        validateMediaPublicationRequest(document.assets, document.tags, post.ownerRef())
        val runs = document.assets.mapIndexed { index, asset ->
            val assetId = requireNotNull(asset.assetId)
            val key = "revision-${UUID.nameUUIDFromBytes("$revisionId:$idempotencyKey:$index".toByteArray())}"
            assetId to mediaAssetProcessor.request(post.ownerRef().key(), assetId, asset.kind, key)
        }.toMap()
        val hasVerifying = document.assets.any { it.sourceStatus == MediaSourceStatus.VERIFYING || it.status == MediaAssetStatus.VERIFYING }
        repository.updatePostEditorRevisionState(revisionId, if (hasVerifying) PostRevisionState.PENDING_SOURCE else PostRevisionState.PROCESSING_MEDIA)
        return reconcilePublication(repository.savePostPublication(PostPublication(
            draftId = post.id, revision = document.revisionNo,
            state = if (hasVerifying) PostPublicationState.PENDING_SOURCE else PostPublicationState.PROCESSING_MEDIA,
            idempotencyKey = idempotencyKey, requestedAt = Instant.now(clock),
            processingRunIds = runs.mapValues { it.value.runId }, revisionId = revisionId
        )))
    }

    /**
     * Records a publication intent immediately.  Media conversion continues in
     * MediaStore; a later reconciliation activates the draft exactly once when
     * all of its current assets are READY.
     */
    fun requestPostPublication(actor: CurrentActor, input: RequestPostPublicationInput): PostPublication {
        require(input.idempotencyKey.trim().length in 8..200) { "idempotencyKey is required" }
        val draft = repository.findStoredPost(input.draftId) ?: throw IllegalArgumentException("Draft not found")
        require(draft.status == ContentStatus.DRAFT) { "Post is already published" }
        requireOwnsContent(actor.activeOwner.ref(), draft.ownerRef())
        require(draft.contentVersion >= MEDIA_POST_CONTENT_VERSION) { "Async publication is available for media projects" }
        validateMediaPublicationRequest(draft.assets, draft.tags, actor.activeOwner.ref())
        val existing = repository.findPostPublication(draft.id)
        if (existing != null && existing.state in PENDING_PUBLICATION_STATES && existing.idempotencyKey == input.idempotencyKey) {
            return reconcilePublication(existing)
        }
        val revision = (existing?.revision ?: 0) + 1
        val runs = linkedMapOf<String, RequestedMediaProcessing>()
        try {
            draft.assets.forEachIndexed { index, asset ->
                val assetId = requireNotNull(asset.assetId)
                val requestKey = "publication-${UUID.nameUUIDFromBytes("${draft.id}:$revision:${input.idempotencyKey}:$index".toByteArray())}"
                runs[assetId] = mediaAssetProcessor.request(draft.ownerRef().key(), assetId, asset.kind, requestKey)
            }
        } catch (error: Throwable) {
            runs.values.forEach { run -> runCatching { mediaAssetProcessor.cancel(draft.ownerRef().key(), run.runId) } }
            throw error
        }
        val hasVerifyingSource = draft.assets.any { asset ->
            uploadedAssetVerifier.status(draft.ownerRef().key(), requireNotNull(asset.assetId)) == MediaAssetStatus.VERIFYING
        }
        val publication = PostPublication(
            draftId = draft.id,
            revision = revision,
            state = if (hasVerifyingSource) PostPublicationState.PENDING_SOURCE else PostPublicationState.PROCESSING_MEDIA,
            idempotencyKey = input.idempotencyKey.trim(),
            requestedAt = Instant.now(clock),
            processingRunIds = runs.mapValues { it.value.runId }
        )
        return reconcilePublication(repository.savePostPublication(publication))
    }

    fun postPublication(actor: CurrentActor, draftId: String): PostPublication {
        val draft = repository.findStoredPost(draftId) ?: throw IllegalArgumentException("Draft not found")
        requireOwnsContent(actor.activeOwner.ref(), draft.ownerRef())
        val publication = repository.findPostPublication(draftId)
            ?: return PostPublication(draftId, 0, PostPublicationState.DRAFT, "", draft.updatedAt)
        return reconcilePublication(publication)
    }

    fun cancelPostPublication(actor: CurrentActor, draftId: String): PostPublication {
        val draft = repository.findStoredPost(draftId) ?: throw IllegalArgumentException("Draft not found")
        requireOwnsContent(actor.activeOwner.ref(), draft.ownerRef())
        val current = repository.findPostPublication(draftId)
            ?: return PostPublication(draftId, 0, PostPublicationState.DRAFT, "", draft.updatedAt)
        require(current.state != PostPublicationState.ACTIVE) { "Published post cannot be cancelled" }
        current.processingRunIds.values.forEach { runId -> runCatching { mediaAssetProcessor.cancel(draft.ownerRef().key(), runId) } }
        // Explicit cancellation returns the frozen revision to an editable
        // draft. Processing runs are generation-scoped, so cancelled results
        // cannot activate this publication after it is unfrozen.
        current.revisionId?.let { repository.updatePostEditorRevisionState(it, PostRevisionState.DRAFT) }
        return repository.savePostPublication(current.copy(state = PostPublicationState.DRAFT, failureAssetIds = emptyList()))
    }

    /** Called by the periodic worker and safely repeatable after duplicate events. */
    fun reconcilePendingPublications(limit: Int = 100) {
        repository.listPendingPostPublications(limit.coerceIn(1, 500)).forEach(::reconcilePublication)
    }

    fun mediaLifecycleCursor(): Long = repository.mediaLifecycleCursor()

    /** Inbox de-duplicates redelivery before reconciliation; the cursor moves
     * only after every returned event has been durably observed. */
    fun consumeMediaLifecycleEvents(events: List<MediaLifecycleEvent>) {
        if (events.isEmpty()) return
        events.sortedBy { it.sequence }.forEach { event ->
            if (repository.recordMediaLifecycleEvent(event.eventId)) {
                // A lifecycle change can only make a pending project ready or
                // actionable. Reconciliation still queries MediaStore, so an
                // event payload is never trusted as the publication truth.
                reconcilePendingPublications()
            }
            repository.updateMediaLifecycleCursor(event.sequence)
        }
    }

    fun publishPostDraft(actor: CurrentActor, draftId: String): Post {
        val draft = repository.findStoredPost(draftId) ?: throw IllegalArgumentException("Draft not found")
        require(draft.status == ContentStatus.DRAFT) { "Post is already published" }
        requireOwnsContent(actor.activeOwner.ref(), draft.ownerRef())
        if (draft.contentVersion >= MEDIA_POST_CONTENT_VERSION) {
            validateMediaProject(draft.assets, draft.tags, actor.activeOwner.ref(), publishing = true)
            val published = repository.updatePost(draft.copy(
                title = null,
                text = "",
                blocks = emptyList(),
                status = ContentStatus.ACTIVE,
                visibility = Visibility.PUBLIC,
                updatedAt = Instant.now(clock)
            ))
            indexPost(published)
            profileUsage.report(actor.activeOwner.ref(), "content", "posts")
            return published
        }
        val blocks = normalizeContentBlocks(draft.blocks)
        validateContentBlocks(blocks)
        require(hasPublishableContent(draft.text, blocks)) { "Post must contain publishable content" }
        val published = repository.updatePost(draft.copy(
            blocks = blocks,
            status = ContentStatus.ACTIVE,
            visibility = Visibility.PUBLIC,
            updatedAt = Instant.now(clock)
        ))
        indexPost(published)
        profileUsage.report(actor.activeOwner.ref(), "content", "posts")
        return published
    }

    /** Creates a public v2 project.  Text/blocks are intentionally discarded. */
    private fun createMediaPost(actor: CurrentActor, input: CreatePostInput): Post {
        val tags = normalizeMediaTags(input.tags, required = true)
        val assets = normalizeMediaAssets(input.assets.orEmpty(), actor.activeOwner.ref())
        validateMediaProject(assets, tags, actor.activeOwner.ref(), publishing = true)
        val now = Instant.now(clock)
        val post = repository.savePost(
            Post(
                id = UUID.randomUUID().toString(),
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                assets = assets,
                contentVersion = MEDIA_POST_CONTENT_VERSION,
                tags = tags,
                allowComments = input.allowComments,
                // v2 projects are always public.  Close-friends posts remain
                // available only through the legacy content path.
                visibility = Visibility.PUBLIC,
                createdAt = now,
                updatedAt = now
            )
        )
        indexPost(post)
        profileUsage.report(actor.activeOwner.ref(), "content", "posts")
        return withStableAssetUrls(post)
    }

    private fun updateMediaPost(actor: CurrentActor, current: Post, input: UpdatePostInput): Post {
        val assets = normalizeMediaAssets(input.assets ?: current.assets, actor.activeOwner.ref())
        val tags = input.tags?.let { normalizeMediaTags(it, required = true) } ?: normalizeMediaTags(current.tags, required = true)
        validateMediaProject(assets, tags, actor.activeOwner.ref(), publishing = true)
        val saved = repository.updatePost(current.copy(
            title = null,
            text = "",
            blocks = emptyList(),
            assets = assets,
            tags = tags,
            allowComments = input.allowComments ?: current.allowComments,
            visibility = Visibility.PUBLIC,
            contentVersion = MEDIA_POST_CONTENT_VERSION,
            updatedAt = Instant.now(clock)
        ))
        indexPost(saved)
        return withStableAssetUrls(saved)
    }

    /** V2 drafts intentionally permit no tags and no assets until publish. */
    private fun saveMediaPostDraft(
        actor: CurrentActor,
        input: SavePostDraftInput,
        current: Post?,
        now: Instant
    ): Post {
        val assets = normalizeMediaAssets(input.assets ?: current?.assets.orEmpty(), actor.activeOwner.ref())
        val tags = normalizeMediaTags(input.tags, required = false)
        val next = current?.copy(
            title = null,
            text = "",
            blocks = emptyList(),
            assets = assets,
            tags = tags,
            allowComments = input.allowComments,
            visibility = Visibility.PUBLIC,
            contentVersion = MEDIA_POST_CONTENT_VERSION,
            updatedAt = now
        ) ?: Post(
            id = UUID.randomUUID().toString(),
            authorId = actor.activeOwner.id,
            ownerType = actor.activeOwner.ownerType,
            ownerId = actor.activeOwner.id,
            author = actor.activeOwner,
            assets = assets,
            tags = tags,
            allowComments = input.allowComments,
            visibility = Visibility.PUBLIC,
            status = ContentStatus.DRAFT,
            contentVersion = MEDIA_POST_CONTENT_VERSION,
            createdAt = now,
            updatedAt = now
        )
        return if (current == null) repository.savePost(next) else repository.updatePost(next)
    }

    fun deletePost(actor: CurrentActor, postId: String) {
        val current = repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        repository.deletePost(postId)
        searchEvents.postDelete(postId)
    }

    fun createStory(author: SessionUser, input: CreateStoryInput): Story =
        createStory(CurrentActor(author, author.asAccountUser()), input)

    fun createStory(actor: CurrentActor, input: CreateStoryInput): Story {
        require(input.blocks.isNotEmpty()) { "Story must contain at least one block" }
        val now = Instant.now(clock)
        val blocks = input.blocks.map { normalizeStoryBlock(it, actor.activeOwner.ref()) }
        val story = repository.saveStory(
            Story(
                id = UUID.randomUUID().toString(),
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                blocks = blocks,
                visibility = input.visibility,
                durationMs = storyDurationMs(blocks),
                mediaDurationMs = storyMediaDurationMs(blocks),
                closeFriends = input.visibility == Visibility.CLOSE_FRIENDS,
                archived = false,
                remainingLifeSeconds = 24 * 60 * 60,
                createdAt = now,
                expiresAt = now.plusSeconds(24 * 60 * 60)
            )
        )
        blocks.forEach { block ->
            val assetId = block.data.stringValue("assetId")
            val generation = block.data.longValue("generation")
            if (assetId != null && generation != null) runCatching {
                mediaAssetProcessor.releaseSource(actor.activeOwner.ref().key(), assetId, generation)
            }
        }
        profileUsage.report(actor.activeOwner.ref(), "content", "story_archive")
        return withStableStoryUrls(story)
    }

    fun createComment(author: SessionUser, input: CreateCommentInput): Comment =
        createComment(CurrentActor(author, author.asAccountUser()), input)

    fun createComment(actor: CurrentActor, input: CreateCommentInput): Comment {
        val post = repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        require(post.allowComments) { "Comments are disabled for this post" }
        val document = normalizeCommentDocument(input.document, input.text)
        val documentText = commentDocumentText(document)
        require(documentText.isNotBlank() || input.blocks.isNotEmpty() || input.attachments.isNotEmpty()) { "Comment text or media is required" }
        val blocks = input.blocks.ifEmpty { if (input.text.isBlank()) emptyList() else listOf(textBlock(input.text.trim())) }
            .withFreshBlockIds()
        val text = documentText.ifBlank { input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() } }
        val requestedParent = input.parentId?.let { repository.findComment(it) ?: throw IllegalArgumentException("Parent comment not found") }
        val replyTarget = input.replyToId?.let { repository.findComment(it) ?: throw IllegalArgumentException("Reply target not found") }
            ?: requestedParent
        require(requestedParent == null || requestedParent.postId == input.postId) { "Parent comment belongs to another post" }
        require(replyTarget == null || replyTarget.postId == input.postId) { "Reply target belongs to another post" }
        val root = replyTarget?.let { target ->
            if (target.parentId == null) target
            else repository.findComment(target.parentId) ?: throw IllegalArgumentException("Root comment not found")
        }
        require(root == null || root.parentId == null) { "Comment replies support one nesting level" }
        require(requestedParent == null || root == null || requestedParent.id == root.id || requestedParent.id == replyTarget?.id) {
            "Reply target belongs to another thread"
        }
        val attachments = normalizeCommentAttachments(input.attachments, actor.activeOwner.ref())
        validateCommentMediaReferences(document, attachments)
        val now = Instant.now(clock)
        val comment = repository.saveComment(
            Comment(
                id = UUID.randomUUID().toString(),
                postId = input.postId,
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                // Do not collapse a reply-to-reply.  Threads uses this direct
                // parent relation to lazily load arbitrary depth.
                parentId = root?.id,
                replyToId = replyTarget?.id,
                text = text,
                document = document,
                blocks = blocks,
                attachments = attachments,
                createdAt = now,
                updatedAt = now
            )
        )
        reindexPostDiscussion(comment.postId)
        return comment
    }

    fun updateComment(actor: CurrentActor, input: UpdateCommentInput): Comment {
        val current = repository.findComment(input.id) ?: throw IllegalArgumentException("Comment not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        require(current.status == ContentStatus.ACTIVE) { "Deleted comments cannot be edited" }
        val blocks = input.blocks ?: current.blocks
        val document = normalizeCommentDocument(input.document ?: current.document, input.text ?: current.text)
        val text = commentDocumentText(document).ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        val attachments = normalizeCommentAttachments(input.attachments ?: current.attachments, actor.activeOwner.ref())
        validateCommentMediaReferences(document, attachments)
        require(text.isNotBlank() || blocks.isNotEmpty() || attachments.isNotEmpty()) { "Comment text or media is required" }
        val next = current.copy(
            text = text,
            document = document,
            blocks = blocks.ifEmpty { if (text.isBlank()) emptyList() else listOf(textBlock(text)) },
            attachments = attachments,
            updatedAt = Instant.now(clock),
            editedAt = Instant.now(clock)
        )
        val saved = repository.updateComment(next)
        reindexPostDiscussion(saved.postId)
        return saved
    }

    fun deleteComment(actor: CurrentActor, commentId: String) {
        val current = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        if (current.status == ContentStatus.DELETED) return
        // A tombstone preserves the direct parent chain and any descendants.
        repository.updateComment(current.copy(
            text = "",
            document = CommentDocumentV1(),
            blocks = emptyList(),
            attachments = emptyList(),
            status = ContentStatus.DELETED,
            pinnedAt = null,
            updatedAt = Instant.now(clock)
        ))
        if (current.parentId == null) {
            val post = repository.findPost(current.postId)
            if (post?.pinnedCommentId == current.id) {
                repository.setPinnedComment(post.id, null, null)
                repository.updatePost(post.copy(pinnedCommentId = null, updatedAt = Instant.now(clock)))
            }
        }
        reindexPostDiscussion(current.postId)
    }

    fun pinComment(actor: CurrentActor, commentId: String, pinned: Boolean): Comment {
        val comment = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        val post = repository.findPost(comment.postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        require(comment.parentId == null) { "Only root comments can be pinned" }
        require(comment.status == ContentStatus.ACTIVE) { "Only active comments can be pinned" }
        val now = Instant.now(clock)
        repository.setPinnedComment(post.id, if (pinned) comment.id else null, if (pinned) now else null)
        repository.updatePost(post.copy(pinnedCommentId = if (pinned) comment.id else null, updatedAt = now))
        val saved = repository.findComment(comment.id)?.copy(updatedAt = now)
            ?: throw IllegalArgumentException("Comment not found")
        reindexPostDiscussion(saved.postId)
        return saved
    }

    fun hideComment(actor: CurrentActor, commentId: String): Comment {
        val comment = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        val post = repository.findPost(comment.postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        // A global hide must not leave the original text or media reachable
        // through a direct lookup. The hidden node stays in persistence only
        // to preserve the direct-parent chain of its descendants.
        val hidden = repository.updateComment(comment.copy(
            text = "",
            document = CommentDocumentV1(),
            blocks = emptyList(),
            attachments = emptyList(),
            status = ContentStatus.HIDDEN,
            pinnedAt = null,
            updatedAt = Instant.now(clock)
        ))
        reindexPostDiscussion(comment.postId)
        return hidden
    }

    fun reportComment(actor: CurrentActor, input: ReportCommentInput): Boolean {
        val comment = repository.findComment(input.commentId) ?: throw IllegalArgumentException("Comment not found")
        val reason = input.reason.trim()
        require(reason.length in 3..500) { "Report reason must be 3 to 500 characters" }
        repository.saveCommentReport(CommentReport(comment.id, actor.activeOwner.ref(), reason, Instant.now(clock)))
        repository.hideCommentForViewer(comment.id, actor.activeOwner.ref())
        return true
    }

    private fun List<ContentBlock>.withFreshBlockIds(): List<ContentBlock> =
        map { block -> block.copy(id = UUID.randomUUID().toString()) }

    fun deleteStory(actor: CurrentActor, storyId: String) {
        val current = repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        repository.deleteStory(storyId)
    }

    fun recordMediaReference(ownerType: String, ownerId: String, blobId: String, profile: String? = null) {
        repository.saveMediaReference(ContentMediaReference(ownerType = ownerType, ownerId = ownerId, blobId = blobId, profile = profile, createdAt = Instant.now(clock)))
    }

    fun canViewMedia(blobId: String, visibilityResolver: (String) -> AccountVisibility): Boolean =
        (repository.listMediaReferences(blobId) + repository.findLegacyMediaReferences(blobId)).any { ref ->
            when (ref.ownerType) {
                "post" -> repository.findPost(ref.ownerId)
                    ?.let { canViewPost(it, visibilityResolver(it.ownerRef().key())) } == true
                "comment" -> repository.findComment(ref.ownerId)
                    ?.let { comment ->
                        repository.findPost(comment.postId)
                            ?.let { canViewPost(it, visibilityResolver(it.ownerRef().key())) } == true
                    } == true
                "story" -> repository.findStory(ref.ownerId)
                    ?.let { story -> canViewStory(story, visibilityResolver(story.ownerRef().key()), includeArchived = story.isArchivedAt(Instant.now(clock))) } == true
                else -> false
            }
        }

    fun resolveStableAssetOwner(
        assetId: String,
        generation: Long?,
        source: Boolean,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility
    ): String? {
        val post = repository.findStoredPostByAssetId(assetId)
        if (post != null) {
            val asset = post.assets.firstOrNull { it.assetId == assetId && it.deliveryContract == "STABLE_V2" } ?: return null
            if (source) {
                if (post.status != ContentStatus.DRAFT || viewer != post.ownerRef()) return null
            } else {
                if (post.status != ContentStatus.ACTIVE || generation == null || asset.generation != generation) return null
                if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return null
            }
            return post.ownerRef().key()
        }
        if (source || generation == null) return null
        val story = repository.findStoryByAssetId(assetId) ?: return null
        val block = story.blocks.firstOrNull {
            it.data.stringValue("assetId") == assetId &&
                it.data.stringValue("deliveryContract") == "STABLE_V2" &&
                it.data.longValue("generation") == generation
        } ?: return null
        val archived = story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(Instant.now(clock))
        if (!canViewStory(story, visibilityResolver(story.ownerRef().key()), includeArchived = archived)) return null
        return story.ownerRef().key()
    }

    fun profileContent(
        ownerId: String,
        visibility: AccountVisibility,
        postLimit: Int,
        storyLimit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { visibility }
    ): ProfileContentResponse {
        val now = Instant.now(clock)
        val owner = OwnerRef(visibility.ownerType, ownerId)
        val posts = repository.listPostsByOwner(owner, postLimit.coerceIn(1, 500))
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, visibility.viewerRef()) }
            .map { withAuthor(it, authorResolver) }
            .map(::withoutEditorTags)
        val stories = repository.listActiveStoriesByOwner(owner, now, storyLimit.coerceIn(1, 50))
            .filter { canViewStory(it, visibility, includeArchived = false) }
            .map { enrichStory(it, now = now, viewer = visibility.viewerRef()) }
        val comments = posts.flatMap { repository.listCommentsForPost(it.id, 3) }
            .map { withCommentViewerState(it, visibility.viewerRef(), authorResolver) }
        val collections = collections(owner, visibility, 80, visibilityResolver)
        return ProfileContentResponse(posts = posts, stories = stories, comments = comments, collections = collections)
    }

    fun createCollection(actor: CurrentActor, input: CreateCollectionInput): SavedCollection {
        val title = normalizeCollectionTitle(input.title)
        val now = Instant.now(clock)
        val collection = repository.saveCollection(
            SavedCollection(
                id = UUID.randomUUID().toString(),
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                title = title,
                description = normalizeCollectionDescription(input.description),
                cover = input.cover,
                visibility = input.visibility,
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.collectionUpsert(collection)
        return collection
    }

    fun updateCollection(actor: CurrentActor, input: UpdateCollectionInput): SavedCollection {
        val current = repository.findCollection(input.id) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), current)
        val next = current.copy(
            title = input.title?.let(::normalizeCollectionTitle) ?: current.title,
            description = if (input.description != null) normalizeCollectionDescription(input.description) else current.description,
            cover = input.cover ?: current.cover,
            visibility = input.visibility ?: current.visibility,
            updatedAt = Instant.now(clock)
        )
        val saved = repository.updateCollection(next)
        searchEvents.collectionUpsert(saved)
        return saved
    }

    fun deleteCollection(actor: CurrentActor, collectionId: String) {
        val current = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), current)
        repository.deleteCollection(collectionId)
        searchEvents.collectionDelete(collectionId)
    }

    fun collections(
        owner: OwnerRef,
        visibility: AccountVisibility,
        limit: Int,
        visibilityResolver: (String) -> AccountVisibility = { visibility }
    ): List<SavedCollection> {
        return repository.listCollectionsByOwner(owner, limit.coerceIn(1, 100))
            .filter { canViewCollection(it, visibility) }
            .map { enrichCollectionForViewer(it, visibilityResolver) }
    }

    fun collection(
        id: String,
        viewer: OwnerRef,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null },
        limit: Int = 200
    ): CollectionDetail {
        val collection = repository.findCollection(id) ?: throw IllegalArgumentException("Collection not found")
        val ownerVisibility = visibilityResolver(collection.ownerRef().key())
        require(canViewCollection(collection, ownerVisibility)) { "Collection not found" }
        val posts = repository.listCollectionPosts(collection.id, limit.coerceIn(1, 500))
            .filter { post -> canViewPost(post, visibilityResolver(post.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
            .map(::withoutEditorTags)
        return CollectionDetail(
            collection = collection.copy(
                itemCount = posts.size,
                previewBlocks = previewBlocks(posts)
            ),
            posts = posts
        )
    }

    fun postCollections(actor: CurrentActor, postId: String): PostCollectionsState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        return PostCollectionsState(postId = postId, collectionIds = repository.listPostCollectionIds(actor.activeOwner.ref(), postId))
    }

    fun setPostCollections(
        actor: CurrentActor,
        input: SetPostCollectionsInput,
        visibilityResolver: (String) -> AccountVisibility
    ): PostCollectionsState {
        val post = repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        require(canViewPost(post, visibilityResolver(post.ownerRef().key()))) { "Post is not available" }
        val owner = actor.activeOwner.ref()
        val desired = input.collectionIds.distinct()
        val desiredCollections = desired.map { id ->
            (repository.findCollection(id) ?: throw IllegalArgumentException("Collection not found")).also {
                requireOwnsCollection(owner, it)
            }
        }
        val current = repository.listPostCollectionIds(owner, input.postId).toSet()
        val desiredSet = desiredCollections.map { it.id }.toSet()
        val now = Instant.now(clock)
        (desiredSet - current).forEach { repository.addPostToCollection(it, input.postId, now) }
        (current - desiredSet).forEach { repository.removePostFromCollection(it, input.postId) }
        (desiredSet + current).forEach { id ->
            repository.findCollection(id)?.let(searchEvents::collectionUpsert)
        }
        return PostCollectionsState(postId = input.postId, collectionIds = repository.listPostCollectionIds(owner, input.postId))
    }

    fun addPostToCollection(
        actor: CurrentActor,
        collectionId: String,
        postId: String,
        visibilityResolver: (String) -> AccountVisibility
    ): PostCollectionsState {
        val current = postCollections(actor, postId).collectionIds.toMutableSet()
        current.add(collectionId)
        return setPostCollections(actor, SetPostCollectionsInput(postId, current.toList()), visibilityResolver)
    }

    fun removePostFromCollection(actor: CurrentActor, collectionId: String, postId: String): PostCollectionsState {
        val collection = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), collection)
        repository.removePostFromCollection(collectionId, postId)
        repository.findCollection(collectionId)?.let(searchEvents::collectionUpsert)
        return PostCollectionsState(postId = postId, collectionIds = repository.listPostCollectionIds(actor.activeOwner.ref(), postId))
    }

    fun search(
        viewer: OwnerRef,
        input: ContentSearchInput,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): ContentSearchResponse {
        val query = input.query.trim()
        if (query.isBlank() && input.tags.isEmpty()) return ContentSearchResponse()
        val requested = normalizeSearchTypes(input.types)
        val limit = input.limit.coerceIn(1, 100)
        val errors = mutableListOf<String>()
        val items = mutableListOf<ContentSearchItem>()
        val postItems = mutableListOf<ContentSearchItem>()

        if ("posts" in requested || "tags" in requested) {
            val result = searchIndex.search("posts", query.ifBlank { input.tags.joinToString(" ") }, limit * 3)
            result.error?.let(errors::add)
            result.hits.mapNotNull { hit ->
                repository.findPost(hit.id)
                    ?.takeIf { post -> matchesSearchFilters(post, input) }
                    ?.takeIf { post -> canViewPost(post, visibilityResolver(post.ownerRef().key())) }
                    ?.let { post ->
                        val enriched = withAuthor(withViewerState(post, viewer), authorResolver)
                        postSearchItem(enriched, hit.score, hit.snippet)
                    }
            }.also { postItems.addAll(it) }
            if ("posts" in requested) items.addAll(postItems)
        }

        if ("collections" in requested) {
            val result = searchIndex.search("collections", query, limit * 3)
            result.error?.let(errors::add)
            items.addAll(result.hits.mapNotNull { hit ->
                repository.findCollection(hit.id)
                    ?.takeIf { collection -> matchesSearchFilters(collection, input) }
                    ?.let { collection ->
                        val visibility = visibilityResolver(collection.ownerRef().key())
                        collection.takeIf { canViewCollection(it, visibility) }
                    }
                    ?.let { collection ->
                        collectionSearchItem(
                            collection = enrichCollectionForViewer(collection, visibilityResolver),
                            owner = authorResolver(collection.ownerRef().key()),
                            score = hit.score,
                            snippet = hit.snippet
                        )
                    }
            })
        }

        if ("tags" in requested) {
            val existing = items.asSequence().flatMap { it.tags.asSequence() }.toSet()
            val tags = (postItems.asSequence().flatMap { it.tags.asSequence() } + input.tags.asSequence())
                .filter(String::isNotBlank)
                .distinct()
                .filter { it !in existing || requested == setOf("tags") }
                .take(limit)
                .map { tag ->
                    ContentSearchItem(
                        type = "TAG",
                        id = tag,
                        title = "#$tag",
                        snippet = "Posts tagged #$tag",
                        url = "/search?tag=$tag",
                        score = 0.5,
                        tags = listOf(tag),
                        meta = mapOf("tag" to tag)
                    )
                }
            items.addAll(tags)
        }

        val visibleItems = items.distinctBy { "${it.type}:${it.id}" }
        val facets = searchFacets(visibleItems, input)
        val sorted = sortSearchItems(visibleItems, input.sort)
            .take(limit)
        val partialErrors = errors.distinct()
        return ContentSearchResponse(
            items = sorted,
            partialErrors = partialErrors,
            facets = facets,
            providerStatuses = listOf(
                ContentProviderStatus(
                    providerKey = "content",
                    label = "Content",
                    status = if (partialErrors.isEmpty()) "ok" else "partial",
                    message = partialErrors.firstOrNull()
                )
            )
        )
    }

    fun suggest(
        viewer: OwnerRef,
        query: String,
        limit: Int,
        visibilityResolver: (String) -> AccountVisibility
    ): ContentSuggestResponse {
        val normalized = query.trim()
        if (normalized.isBlank()) return ContentSuggestResponse()
        val response = search(
            viewer = viewer,
            input = ContentSearchInput(query = normalized, types = listOf("posts", "collections", "tags"), limit = limit.coerceIn(1, 20)),
            visibilityResolver = visibilityResolver
        )
        val suggestions = response.items
            .flatMap { item ->
                buildList {
                    item.title?.takeIf(String::isNotBlank)?.let { add(ContentSuggestion(item.type, it, it)) }
                    item.tags.forEach { tag -> add(ContentSuggestion("TAG", tag, "#$tag")) }
                }
            }
            .distinctBy { "${it.type}:${it.value.lowercase()}" }
            .take(limit.coerceIn(1, 20))
        return ContentSuggestResponse(suggestions = suggestions, partialErrors = response.partialErrors)
    }

    fun feed(
        viewerId: String,
        tagAffinity: Set<String>,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<FeedItem> =
        feed(OwnerRef(OwnerType.USER, viewerId), tagAffinity, limit, authorResolver)

    fun feed(
        viewer: OwnerRef,
        tagAffinity: Set<String>,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): List<FeedItem> {
        return repository.listRecentPosts(limit.coerceIn(1, 100) * 3)
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
            .map { post ->
                val tagScore = post.tags.count { it in tagAffinity } * 4.0
                val likeScore = post.likeCount.coerceAtMost(30) * 0.15
                val ageHours = java.time.Duration.between(post.createdAt, Instant.now(clock)).toHours().coerceAtLeast(0)
                val recencyScore = 1.0 / (1 + ageHours).toDouble()
                val ownPenalty = if (post.ownerType == viewer.ownerType && post.ownerId == viewer.ownerId) -2.0 else 0.0
                FeedItem(
                    // Tags are recommendation-only and never part of a feed
                    // payload, including the creator's own feed result.
                    post = withoutEditorTags(post),
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

    fun recommendationFeed(
        viewerId: String,
        input: RecommendationFeedInput,
        socialGraph: AccountSocialGraph = AccountSocialGraph(),
        authorResolver: (String) -> AccountUser? = { null }
    ): RecommendationFeedResponse =
        recommendationFeed(OwnerRef(OwnerType.USER, viewerId), input, socialGraph, authorResolver)

    fun recommendationFeed(
        viewer: OwnerRef,
        input: RecommendationFeedInput,
        socialGraph: AccountSocialGraph = AccountSocialGraph(),
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): RecommendationFeedResponse {
        val pageLimit = input.limit.coerceIn(1, 50)
        val seed = input.sessionSeed.ifBlank { "default" }
        val blockedIds = socialGraph.blockedIds.toSet()
        val affinityRank = repository.listViewerTagAffinity(viewer, 80)
        val tagAffinity = affinityRank.toSet()
        val now = Instant.now(clock)
        val candidates = repository.listRecentPosts(500)
            .filter { it.ownerId !in blockedIds }
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
            .map { post -> scoreRecommendation(post, viewer, tagAffinity, socialGraph, now) }

        val ordered = stableRecommendationOrder(candidates, seed)
        // Layout assignment deliberately does not use the legacy session seed:
        // opening the same feed from another device must not change an unplaced
        // post's salt or coordinate.
        val placementsByPost = candidates
            .sortedWith(compareByDescending<ScoredRecommendation> { it.score }.thenByDescending { it.post.createdAt }.thenBy { it.post.id })
            .associate { scored ->
                val constellationKey = recommendationConstellationKey(scored.post, affinityRank)
                scored.post.id to repository.reserveRecommendationPlacement(
                    viewer = viewer,
                    postId = scored.post.id,
                    constellationKey = constellationKey,
                    constellationFactory = { existing -> recommendationConstellation(viewer, constellationKey, existing) },
                    placementFactory = { constellation, occupied ->
                        recommendationPlacement(viewer, scored.post, constellation, occupied)
                    }
                )
            }
        val page = ordered
            .mapNotNull { scored -> placementsByPost[scored.post.id]?.let { scored to it } }
            .filter { (_, placement) ->
                worldChunk(placement.worldX) == input.chunkX && worldChunk(placement.worldY) == input.chunkY
            }
            .take(pageLimit)

        return RecommendationFeedResponse(
            chunkX = input.chunkX,
            chunkY = input.chunkY,
            sessionSeed = seed,
            items = page.mapIndexed { index, (scored, placement) ->
                RecommendationFeedItem(
                    post = withoutEditorTags(scored.post),
                    score = scored.score,
                    reasons = scored.reasons,
                    cell = FeedCell(q = index % FEED_CELL_COLUMNS, r = index / FEED_CELL_COLUMNS),
                    emphasis = scored.emphasis,
                    placement = placement
                )
            },
            constellations = repository.listRecommendationConstellations(
                viewer,
                placementsByPost.values.map { it.constellationKey }.toSet()
            )
        )
    }

    fun post(
        id: String,
        viewerId: String? = null,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        post(id, viewerId?.let { OwnerRef(OwnerType.USER, it) }, authorResolver)

    fun post(
        id: String,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        repository.findPost(id)
            ?.let { withViewerState(it, viewer) }
            ?.let { withAuthor(it, authorResolver) }
            ?.let { revealEditorTagsToOwner(it, viewer) }

    fun post(
        id: String,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        repository.findPost(id)
            ?.takeIf { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            ?.let { withViewerState(it, viewer) }
            ?.let { withAuthor(it, authorResolver) }
            ?.let { revealEditorTagsToOwner(it, viewer) }

    fun likePost(user: SessionUser, postId: String): PostReactionState =
        likePost(CurrentActor(user, user.asAccountUser()), postId)

    fun likePost(actor: CurrentActor, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, actor.activeOwner.ref(), true)
        return PostReactionState(postId = postId, liked = true, likeCount = repository.countPostLikes(postId))
    }

    fun unlikePost(user: SessionUser, postId: String): PostReactionState =
        unlikePost(CurrentActor(user, user.asAccountUser()), postId)

    fun unlikePost(actor: CurrentActor, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, actor.activeOwner.ref(), false)
        return PostReactionState(postId = postId, liked = false, likeCount = repository.countPostLikes(postId))
    }

    fun votePoll(actor: CurrentActor, input: PollVoteInput): PollVoteState {
        val post = repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        val poll = post.blocks.firstOrNull { it.id == input.blockId && it.type == ContentBlockType.POLL }
            ?: throw IllegalArgumentException("Poll not found")
        require(poll.data["closed"]?.jsonPrimitive?.booleanOrNull != true) { "Poll is closed" }
        val options = pollOptionIds(poll)
        require(input.optionId in options) { "Poll option not found" }
        repository.setPollVote(post.id, poll.id, actor.activeOwner.ref(), input.optionId)
        return PollVoteState(
            postId = post.id,
            blockId = poll.id,
            optionId = input.optionId,
            counts = repository.pollVoteCounts(post.id, poll.id),
            closed = false
        )
    }

    fun closePoll(actor: CurrentActor, postId: String, blockId: String): PollVoteState {
        val post = repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), post.ownerRef())
        val updatedBlocks = post.blocks.map { block ->
            if (block.id == blockId && block.type == ContentBlockType.POLL) {
                block.copy(data = JsonObject(block.data + ("closed" to JsonPrimitive(true))))
            } else block
        }
        require(updatedBlocks != post.blocks) { "Poll not found" }
        repository.updatePost(post.copy(blocks = updatedBlocks, updatedAt = Instant.now(clock)))
        return PollVoteState(post.id, blockId, repository.pollVoteForActor(post.id, blockId, actor.activeOwner.ref()) ?: "", repository.pollVoteCounts(post.id, blockId), true)
    }

    fun likeStory(user: SessionUser, storyId: String): StoryReactionState =
        likeStory(CurrentActor(user, user.asAccountUser()), storyId)

    fun likeStory(actor: CurrentActor, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, actor.activeOwner.ref(), true)
        return StoryReactionState(storyId = storyId, liked = true, likeCount = repository.countStoryLikes(storyId))
    }

    fun unlikeStory(user: SessionUser, storyId: String): StoryReactionState =
        unlikeStory(CurrentActor(user, user.asAccountUser()), storyId)

    fun unlikeStory(actor: CurrentActor, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, actor.activeOwner.ref(), false)
        return StoryReactionState(storyId = storyId, liked = false, likeCount = repository.countStoryLikes(storyId))
    }

    fun likeComment(user: SessionUser, commentId: String): CommentReactionState =
        likeComment(CurrentActor(user, user.asAccountUser()), commentId)

    fun likeComment(actor: CurrentActor, commentId: String): CommentReactionState {
        val comment = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, actor.activeOwner.ref(), true)
        reindexPostDiscussion(comment.postId)
        return CommentReactionState(commentId = commentId, liked = true, likeCount = repository.countCommentLikes(commentId))
    }

    fun unlikeComment(user: SessionUser, commentId: String): CommentReactionState =
        unlikeComment(CurrentActor(user, user.asAccountUser()), commentId)

    fun unlikeComment(actor: CurrentActor, commentId: String): CommentReactionState {
        val comment = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, actor.activeOwner.ref(), false)
        reindexPostDiscussion(comment.postId)
        return CommentReactionState(commentId = commentId, liked = false, likeCount = repository.countCommentLikes(commentId))
    }

    fun story(id: String, viewerId: String? = null): Story? =
        story(id, viewerId?.let { OwnerRef(OwnerType.USER, it) })

    fun story(id: String, viewer: OwnerRef?): Story? =
        repository.findStory(id)?.let { enrichStory(it, now = Instant.now(clock), viewer = viewer) }

    fun story(id: String, viewer: OwnerRef?, visibilityResolver: (String) -> AccountVisibility): Story? =
        repository.findStory(id)
            ?.takeIf { canViewStory(it, visibilityResolver(it.ownerRef().key()), includeArchived = it.isArchivedAt(Instant.now(clock))) }
            ?.let { enrichStory(it, now = Instant.now(clock), viewer = viewer) }

    fun comments(
        postId: String,
        limit: Int,
        viewerId: String? = null,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> =
        comments(postId, limit, viewerId?.let { OwnerRef(OwnerType.USER, it) }, authorResolver)

    fun comments(
        postId: String,
        limit: Int,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> =
        repository.listCommentsForPost(postId, limit.coerceIn(1, 100))
            .map { withCommentViewerState(it, viewer, authorResolver) }

    fun comments(
        postId: String,
        limit: Int,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> {
        val post = repository.findPost(postId) ?: return emptyList()
        if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return emptyList()
        return repository.listCommentsForPost(postId, limit.coerceIn(1, 100))
            .map { withCommentViewerState(it, viewer, authorResolver) }
    }

    fun commentThread(
        input: CommentThreadInput,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): CommentThreadResponse {
        val post = repository.findPost(input.postId) ?: return CommentThreadResponse(sort = input.sort)
        if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return CommentThreadResponse(sort = input.sort)
        input.parentId?.let { parentId ->
            val parent = repository.findComment(parentId) ?: throw IllegalArgumentException("Root comment not found")
            require(parent.postId == input.postId && parent.parentId == null) { "Replies can only be requested for a root comment" }
        }
        val hidden = viewer?.let { repository.hiddenCommentIdsForViewer(input.postId, it) }.orEmpty()
        // A direct-child page is assembled from the complete local thread so
        // cursor ordering is deterministic even when descendants are loaded
        // separately.  Persistence still filters by post and uses its index.
        val comments = repository.listCommentsForPost(input.postId, COMMENT_THREAD_SCAN_LIMIT)
            .filterNot { it.id in hidden }
            .map { withCommentViewerState(it, viewer, authorResolver) }
        val replyCountByParent = comments.asSequence()
            .mapNotNull { comment -> comment.parentId?.let { parentId -> parentId to comment } }
            .groupingBy { it.first }
            .eachCount()
        val direct = comments.filter { it.parentId == input.parentId }
        val sorted = if (input.parentId == null) sortCommentChildren(direct, input.sort, true)
        else direct.sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
        val afterId = decodeCommentCursor(input.cursor)
        val start = afterId?.let { id -> sorted.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        val pageSize = input.limit.coerceIn(1, 100)
        val page = sorted.drop(start).take(pageSize)
        val nextCursor = page.lastOrNull()
            ?.takeIf { start + page.size < sorted.size }
            ?.let { encodeCommentCursor(it.id) }
        val legacyReplies = if (input.parentId == null) comments
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
        else emptyMap()
        val rendered = page.map { comment ->
            comment.copy(
                replyCount = replyCountByParent[comment.id] ?: 0,
                // Kept as a one-level compatibility projection only.  The
                // page itself always contains direct children of parentId.
                replies = legacyReplies[comment.id].orEmpty().sortedBy { it.createdAt }
            )
        }
        return CommentThreadResponse(
            comments = rendered,
            totalCount = direct.size,
            sort = input.sort,
            parentId = input.parentId,
            nextCursor = nextCursor
        )
    }

    private fun sortCommentChildren(comments: List<Comment>, sort: CommentSort, isRoot: Boolean): List<Comment> {
        val sorted = when (sort) {
            CommentSort.TOP -> comments.sortedWith(compareByDescending<Comment> { it.likeCount }.thenByDescending { it.createdAt }.thenBy { it.id })
            CommentSort.NEWEST -> comments.sortedWith(compareByDescending<Comment> { it.createdAt }.thenBy { it.id })
            CommentSort.OLDEST -> comments.sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
        }
        if (!isRoot) return sorted
        val pinned = sorted.filter { it.pinnedAt != null }
        return pinned + sorted.filterNot { it.pinnedAt != null }
    }

    private fun encodeCommentCursor(id: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())

    private fun decodeCommentCursor(cursor: String?): String? = cursor
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { String(java.util.Base64.getUrlDecoder().decode(encoded)) }.getOrNull() }

    fun comment(
        id: String,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): Comment? =
        repository.findComment(id)
            ?.takeIf { it.status == ContentStatus.ACTIVE }
            ?.let { withCommentViewerState(it, viewer, authorResolver) }

    /** Direct Threads permalink lookup. Deleted/author-hidden comments remain
     * as structural tombstones, while a viewer's personal report hide still
     * prevents the comment from being disclosed through a direct URL. */
    fun comment(
        id: String,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): Comment? {
        val comment = repository.findComment(id) ?: return null
        val post = repository.findPost(comment.postId) ?: return null
        if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return null
        if (viewer != null && id in repository.hiddenCommentIdsForViewer(comment.postId, viewer)) return null
        return withCommentViewerState(comment, viewer, authorResolver)
    }

    fun storiesFeed(
        viewerId: String,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) }
    ): List<StoryRailItem> =
        storiesFeed(OwnerRef(OwnerType.USER, viewerId), limit, authorResolver, visibilityResolver)

    fun storiesFeed(
        viewer: OwnerRef,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): List<StoryRailItem> {
        val now = Instant.now(clock)
        return repository.listActiveStories(now, limit.coerceIn(1, 100))
            .filter { story -> canViewStory(story, visibilityResolver(story.ownerKey()), includeArchived = false) }
            .groupBy { it.ownerKey() }
            .values
            .map { stories ->
                val latest = stories.maxBy { it.createdAt }
                val oldest = stories.minBy { it.createdAt }
                val author = authorResolver(latest.ownerKey())
                StoryRailItem(
                    authorId = latest.authorId,
                    ownerType = latest.ownerType,
                    ownerId = latest.ownerId,
                    authorName = author?.username ?: if (latest.ownerType == viewer.ownerType && latest.ownerId == viewer.ownerId) "You" else "User",
                    author = author,
                    avatarUrl = author?.avatarUrl,
                    storyIds = stories.sortedBy { it.createdAt }.map { it.id },
                    activeCount = stories.size,
                    seen = stories.all { repository.isStoryViewed(it.id, viewer) },
                    closeFriends = stories.any { it.visibility == Visibility.CLOSE_FRIENDS },
                    isViewer = latest.ownerType == viewer.ownerType && latest.ownerId == viewer.ownerId,
                    oldestAt = oldest.createdAt,
                    latestAt = latest.createdAt
                )
            }
            .sortedWith(compareByDescending<StoryRailItem> { it.isViewer }.thenByDescending { it.latestAt })
            .take(limit.coerceIn(1, 100))
    }

    fun storyGroup(
        viewerId: String,
        authorId: String,
        ownerType: OwnerType = OwnerType.USER,
        startStoryId: String?,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) },
        archive: Boolean = false
    ): StoryGroup =
        storyGroup(OwnerRef(OwnerType.USER, viewerId), authorId, ownerType, startStoryId, authorResolver, visibilityResolver, archive)

    fun storyGroup(
        viewer: OwnerRef,
        authorId: String,
        ownerType: OwnerType = OwnerType.USER,
        startStoryId: String?,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) },
        archive: Boolean = false
    ): StoryGroup {
        val now = Instant.now(clock)
        val owner = OwnerRef(ownerType, authorId)
        val ownerKey = owner.key()
        val visibility = visibilityResolver(ownerKey)
        val rawStories = if (archive) {
            repository.listArchivedStoriesByOwner(owner, now, 100, null)
        } else {
            repository.listActiveStoriesByOwner(owner, now, 100)
        }
        val author = authorResolver(ownerKey)
        val stories = rawStories
            .filter { canViewStory(it, visibility, includeArchived = archive) }
            .sortedBy { it.createdAt }
            .map { enrichStory(it, author, now, viewer) }
        return StoryGroup(
            authorId = authorId,
            ownerType = ownerType,
            ownerId = authorId,
            authorName = author?.username ?: if (owner.ownerType == viewer.ownerType && owner.ownerId == viewer.ownerId) "You" else "User",
            author = author,
            avatarUrl = author?.avatarUrl,
            stories = stories,
            startStoryId = startStoryId?.takeIf { id -> stories.any { it.id == id } } ?: stories.firstOrNull()?.id,
            archive = archive
        )
    }

    fun storyArchive(
        ownerId: String,
        visibility: AccountVisibility,
        author: AccountUser?,
        limit: Int,
        cursor: Instant? = null
    ): StoryArchiveResponse {
        val now = Instant.now(clock)
        if (visibility.isBlocked || !visibility.canSeePrivateContent) {
            return StoryArchiveResponse(ownerId = ownerId, owner = author)
        }
        val pageLimit = limit.coerceIn(1, 80)
        val stories = repository.listArchivedStoriesByOwner(OwnerRef(visibility.ownerType, ownerId), now, pageLimit + 1, cursor)
            .filter { canViewStory(it, visibility, includeArchived = true) }
            .map { enrichStory(it, author, now, visibility.viewerRef()) }
        val page = stories.take(pageLimit)
        return StoryArchiveResponse(
            ownerId = ownerId,
            ownerType = visibility.ownerType,
            owner = author,
            stories = page,
            cursor = cursor?.toString(),
            nextCursor = page.lastOrNull()?.takeIf { stories.size > pageLimit }?.createdAt?.toString()
        )
    }

    fun storyArchivePeriods(ownerId: String, visibility: AccountVisibility, limit: Int = 60): StoryArchivePeriodsResponse {
        if (visibility.isBlocked || !visibility.canSeePrivateContent) {
            return StoryArchivePeriodsResponse(ownerId = ownerId, ownerType = visibility.ownerType)
        }
        val now = Instant.now(clock)
        val owner = OwnerRef(visibility.ownerType, ownerId)
        // Visibility may differ per story; calculate periods from visible records rather than exposing aggregate counts.
        val visible = repository.listArchivedStoriesByOwner(owner, now, 1_000, null)
            .filter { canViewStory(it, visibility, includeArchived = true) }
            .groupBy { it.createdAt.toString().take(7) }
            .entries
            .sortedByDescending { it.key }
            .take(limit.coerceIn(1, 120))
            .map { (period, stories) -> StoryArchivePeriod(period, stories.size, stories.maxByOrNull { it.createdAt }?.id) }
        return StoryArchivePeriodsResponse(ownerId = ownerId, ownerType = visibility.ownerType, periods = visible)
    }

    fun recordStoryView(user: SessionUser, storyId: String): Boolean =
        recordStoryView(CurrentActor(user, user.asAccountUser()), storyId)

    fun recordStoryView(actor: CurrentActor, storyId: String): Boolean {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.recordStoryView(storyId, actor.activeOwner.ref(), Instant.now(clock))
        return true
    }

    fun recordPostView(user: SessionUser, postId: String, durationMs: Long = 0): Boolean =
        recordPostView(CurrentActor(user, user.asAccountUser()), postId, durationMs)

    fun recordPostView(actor: CurrentActor, postId: String, durationMs: Long = 0): Boolean {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.recordPostView(postId, actor.activeOwner.ref(), durationMs, Instant.now(clock))
        return true
    }

    private fun scoreRecommendation(
        post: Post,
        viewer: OwnerRef,
        tagAffinity: Set<String>,
        socialGraph: AccountSocialGraph,
        now: Instant
    ): ScoredRecommendation {
        val tagMatches = post.tags.count { it in tagAffinity }
        val tagScore = (tagMatches / 3.0).coerceIn(0.0, 1.0) * 25.0
        val discussion = repository.findPostSearchProjection(post.id)?.discussion.orEmpty().lowercase()
        val discussionMatches = tagAffinity.count { term -> term.length >= 2 && discussion.contains(term.lowercase()) }
        val semanticRaw = if (tagAffinity.isEmpty()) 0.0 else ((tagMatches + discussionMatches * 1.5) / tagAffinity.size).coerceIn(0.0, 1.0)
        val semanticScore = semanticRaw * 45.0
        val friendScore = if (post.authorId in socialGraph.friendIds) 8.0 else 0.0
        val followingScore = if (post.authorId in socialGraph.followingIds) 4.0 else 0.0
        val likeScore = (post.likeCount.coerceAtMost(40) / 40.0) * 4.0
        val popularityScore = (repository.countPostViews(post.id).coerceAtMost(80) / 80.0) * 3.0
        val ageHours = java.time.Duration.between(post.createdAt, now).toHours().coerceAtLeast(0)
        val recencyScore = 1.0 / (1 + ageHours).toDouble()
        val explorationScore = (stableHash("${viewer.key()}:explore:${post.id}") % 10_001) / 10_000.0 * 10.0
        val ownPenalty = if (post.ownerType == viewer.ownerType && post.ownerId == viewer.ownerId) -4.0 else 0.0
        val viewerViews = repository.countPostViewsByUser(post.id, viewer)
        val viewPenalty = viewerViews.coerceAtMost(5) * -1.2
        val score = semanticScore + tagScore + friendScore + followingScore + likeScore + popularityScore + recencyScore + explorationScore + ownPenalty + viewPenalty

        return ScoredRecommendation(
            post = post,
            score = score,
            reasons = buildList {
                if (friendScore > 0) add("friend")
                else if (followingScore > 0) add("following")
                if (discussionMatches > 0) add("discussion-semantic")
                if (tagScore > 0) add("tag-affinity")
                if (post.likeCount > 0) add("popular")
                if (popularityScore > 0) add("viewed-by-others")
                if (viewerViews > 0) add("seen-before")
                add("exploration")
                add("recent")
            },
            emphasis = when {
                post.assets.any { it.kind == PostAssetKind.VIDEO } || post.blocks.any { it.type == ContentBlockType.VIDEO } || score >= 8.0 -> FeedEmphasis.hero
                post.assets.any { it.kind == PostAssetKind.IMAGE } || post.blocks.any { it.type == ContentBlockType.IMAGE } || score >= 3.0 -> FeedEmphasis.standard
                else -> FeedEmphasis.compact
            }
        )
    }

    private fun recommendationConstellationKey(post: Post, affinityRank: List<String>): String {
        val tags = post.tags
            .asSequence()
            .map(::normalizeConstellationKey)
            .filter { it != MIX_CONSTELLATION_KEY }
            .distinct()
            .toSet()
        val affinityKey = affinityRank
            .asSequence()
            .map(::normalizeConstellationKey)
            .firstOrNull { it in tags }
        return affinityKey ?: tags.sorted().firstOrNull() ?: MIX_CONSTELLATION_KEY
    }

    private fun normalizeConstellationKey(value: String): String =
        value.trim()
            .removePrefix("#")
            .lowercase()
            .replace(CONSTELLATION_KEY_SEPARATOR, "-")
            .trim('-')
            .take(48)
            .ifBlank { MIX_CONSTELLATION_KEY }

    private fun recommendationConstellation(
        viewer: OwnerRef,
        key: String,
        existing: List<RecommendationConstellation>
    ): RecommendationConstellation {
        if (existing.isEmpty()) {
            return RecommendationConstellation(
                key = key,
                anchorX = 0.0,
                anchorY = 0.0
            )
        }

        val hash = stableHash("${viewer.key()}:constellation:$key")
        val base = existing.sortedBy { it.key }[hash % existing.size]
        val distance = CONSTELLATION_MIN_DISTANCE +
            (stableHash("${viewer.key()}:constellation-distance:$key") % (CONSTELLATION_MAX_DISTANCE - CONSTELLATION_MIN_DISTANCE + 1))
        val initialAngle = (hash % 360) * PI / 180.0
        repeat(CONSTELLATION_ANCHOR_PROBES) { probe ->
            val angle = initialAngle + probe * GOLDEN_ANGLE
            val candidate = RecommendationConstellation(
                key = key,
                anchorX = base.anchorX + cos(angle) * distance,
                anchorY = base.anchorY + sin(angle) * distance
            )
            if (existing.none { anchorDistanceSquared(candidate, it) < CONSTELLATION_ANCHOR_CLEARANCE * CONSTELLATION_ANCHOR_CLEARANCE }) {
                return candidate
            }
        }

        // Every probe preserves the required distance from the selected base;
        // this fallback only runs in a densely populated constellation map.
        val fallbackAngle = initialAngle + CONSTELLATION_ANCHOR_PROBES * GOLDEN_ANGLE
        return RecommendationConstellation(
            key = key,
            anchorX = base.anchorX + cos(fallbackAngle) * distance,
            anchorY = base.anchorY + sin(fallbackAngle) * distance
        )
    }

    private fun recommendationPlacement(
        viewer: OwnerRef,
        post: Post,
        constellation: RecommendationConstellation,
        occupied: List<RecommendationPlacement>
    ): RecommendationPlacement {
        val siblings = occupied.filter { it.constellationKey == constellation.key }
        val salt = siblings.size
        val previous = siblings.maxWithOrNull(compareBy<RecommendationPlacement> { it.orbitOrder }.thenBy { it.salt })
        val sizePreset = recommendationSizePreset(post)
        val (nodeWidth, nodeHeight) = recommendationNodeSize(sizePreset)
        if (occupied.isEmpty()) {
            return RecommendationPlacement(
                constellationKey = constellation.key,
                salt = salt,
                worldX = constellation.anchorX - nodeWidth / 2,
                worldY = constellation.anchorY - nodeHeight / 2,
                orbitOrder = salt,
                sizePreset = sizePreset
            )
        }
        val previousSize = previous?.let { recommendationNodeSize(it.sizePreset) }
        val originX = previous?.worldX?.plus((previousSize?.first ?: nodeWidth) / 2) ?: constellation.anchorX
        val originY = previous?.worldY?.plus((previousSize?.second ?: nodeHeight) / 2) ?: constellation.anchorY
        val hash = stableHash("${viewer.key()}:${post.id}:$salt")
        val initialAngle = (hash % 360) * PI / 180.0
        val references = (if (siblings.isNotEmpty()) siblings else occupied)
            .sortedWith(compareBy<RecommendationPlacement> { anchorDistanceForPlacement(it, constellation) }.thenBy { it.salt })
            .take(16)

        // Placement v3 packs a new node against a real neighbour instead of
        // continuing a long random walk. Candidate ordering is deterministic,
        // while global collision checks keep different themes separated.
        references.forEachIndexed { referenceIndex, reference ->
            val (referenceWidth, referenceHeight) = recommendationNodeSize(reference.sizePreset)
            val referenceCenterX = reference.worldX + referenceWidth / 2
            val referenceCenterY = reference.worldY + referenceHeight / 2
            repeat(16) { directionIndex ->
                val angle = initialAngle + (directionIndex + referenceIndex * 3) * (PI / 8)
                val radialX = referenceWidth / 2 + nodeWidth / 2 + POST_PROTECTED_GAP
                val radialY = referenceHeight / 2 + nodeHeight / 2 + POST_PROTECTED_GAP
                val worldX = referenceCenterX + cos(angle) * radialX - nodeWidth / 2
                val worldY = referenceCenterY + sin(angle) * radialY - nodeHeight / 2
                if (occupied.none { nodeBoxesConflict(worldX, worldY, sizePreset, it.worldX, it.worldY, it.sizePreset) }) {
                    return RecommendationPlacement(constellation.key, salt, worldX, worldY, salt, sizePreset, 3)
                }
            }
        }

        val initialDistance = POST_STEP_MIN + (stableHash("${viewer.key()}:step:${post.id}:$salt") % (POST_STEP_MAX - POST_STEP_MIN + 1))

        repeat(POST_PLACEMENT_PROBES) { probe ->
            val angle = initialAngle + probe * GOLDEN_ANGLE
            val distance = initialDistance + probe * POST_PROBE_DISTANCE
            val worldX = originX + cos(angle) * distance - nodeWidth / 2
            val worldY = originY + sin(angle) * distance - nodeHeight / 2
            if (occupied.none { nodeBoxesConflict(worldX, worldY, sizePreset, it.worldX, it.worldY, it.sizePreset) }) {
                return RecommendationPlacement(
                    constellationKey = constellation.key,
                    salt = salt,
                    worldX = worldX,
                    worldY = worldY,
                    orbitOrder = salt,
                    sizePreset = sizePreset,
                    placementVersion = 3
                )
            }
        }
        error("Could not allocate a collision-free recommendation placement")
    }

    private fun anchorDistanceForPlacement(placement: RecommendationPlacement, constellation: RecommendationConstellation): Double {
        val (width, height) = recommendationNodeSize(placement.sizePreset)
        val dx = placement.worldX + width / 2 - constellation.anchorX
        val dy = placement.worldY + height / 2 - constellation.anchorY
        return dx * dx + dy * dy
    }

    private fun nodeBoxesConflict(candidateX: Double, candidateY: Double, candidatePreset: AssetSizePreset, occupiedX: Double, occupiedY: Double, occupiedPreset: AssetSizePreset): Boolean {
        val (candidateWidth, candidateHeight) = recommendationNodeSize(candidatePreset)
        val (occupiedWidth, occupiedHeight) = recommendationNodeSize(occupiedPreset)
        val separated = candidateX + candidateWidth + POST_PROTECTED_GAP <= occupiedX ||
            occupiedX + occupiedWidth + POST_PROTECTED_GAP <= candidateX ||
            candidateY + candidateHeight + POST_PROTECTED_GAP <= occupiedY ||
            occupiedY + occupiedHeight + POST_PROTECTED_GAP <= candidateY
        return !separated
    }

    private fun recommendationSizePreset(post: Post): AssetSizePreset = when {
        post.assets.isNotEmpty() && post.assets.all { it.kind == PostAssetKind.AUDIO } -> AssetSizePreset.S
        post.assets.size >= 3 -> AssetSizePreset.L
        else -> AssetSizePreset.M
    }

    private fun recommendationNodeSize(preset: AssetSizePreset): Pair<Double, Double> = when (preset) {
        AssetSizePreset.S -> 288.0 to 230.0
        AssetSizePreset.M -> 348.0 to 278.0
        AssetSizePreset.L -> 432.0 to 344.0
    }

    private fun anchorDistanceSquared(left: RecommendationConstellation, right: RecommendationConstellation): Double {
        val dx = left.anchorX - right.anchorX
        val dy = left.anchorY - right.anchorY
        return dx * dx + dy * dy
    }

    private fun worldChunk(value: Double): Int = floor(value / WORLD_CHUNK_SIZE).toInt()

    private fun stableRecommendationOrder(candidates: List<ScoredRecommendation>, seed: String): List<ScoredRecommendation> {
        val ranked = candidates
            .sortedWith(compareByDescending<ScoredRecommendation> { it.score }.thenByDescending { it.post.createdAt }.thenBy { stableHash("${seed}:rank:${it.post.id}") })
        val explore = candidates.sortedBy { stableHash("${seed}:explore:${it.post.id}") }
        val used = mutableSetOf<String>()
        var rankedIndex = 0
        var exploreIndex = 0
        val ordered = mutableListOf<ScoredRecommendation>()

        while (ordered.size < candidates.size) {
            val useExplore = (ordered.size + 1) % EXPLORATION_INTERVAL == 0
            val next = if (useExplore) {
                nextUnused(explore, used, exploreIndex).also { exploreIndex = it.second }.first
            } else {
                nextUnused(ranked, used, rankedIndex).also { rankedIndex = it.second }.first
            } ?: nextUnused(ranked, used, rankedIndex).also { rankedIndex = it.second }.first
                ?: nextUnused(explore, used, exploreIndex).also { exploreIndex = it.second }.first
                ?: break

            used.add(next.post.id)
            ordered.add(if (useExplore) next.copy(reasons = (next.reasons + "explore").distinct()) else next)
        }
        return ordered
    }

    private fun nextUnused(
        items: List<ScoredRecommendation>,
        used: Set<String>,
        startIndex: Int
    ): Pair<ScoredRecommendation?, Int> {
        var index = startIndex
        while (index < items.size) {
            val item = items[index]
            index += 1
            if (item.post.id !in used) return item to index
        }
        return null to index
    }

    private fun stableHash(value: String): Int {
        var result = 2166136261u
        value.forEach { char ->
            result = result xor char.code.toUInt()
            result *= 16777619u
        }
        return result.toInt() and Int.MAX_VALUE
    }

    private fun canViewStory(story: Story, visibility: AccountVisibility, includeArchived: Boolean): Boolean {
        if (visibility.isBlocked) return false
        val archived = story.isArchivedAt(Instant.now(clock))
        if (includeArchived != archived) return false
        if (story.status == ContentStatus.DELETED) return false
        if (story.ownerId == visibility.viewerId && story.ownerType == visibility.viewerType) return true
        if (visibility.ownerId != story.ownerId || visibility.ownerType != story.ownerType) return false
        return when (story.visibility) {
            Visibility.PUBLIC -> visibility.canSeePrivateContent
            Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
        }
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

    private fun canViewCollection(collection: SavedCollection, visibility: AccountVisibility): Boolean {
        if (visibility.isBlocked || !visibility.canSeePrivateContent) return false
        if (collection.ownerId != visibility.ownerId || collection.ownerType != visibility.ownerType) return false
        val owner = collection.ownerId == visibility.viewerId && collection.ownerType == visibility.viewerType
        return owner || collection.visibility == CollectionVisibility.PUBLIC
    }

    private fun enrichCollectionForViewer(collection: SavedCollection, visibilityResolver: (String) -> AccountVisibility): SavedCollection {
        val posts = repository.listCollectionPosts(collection.id, 500)
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
        return collection.copy(
            itemCount = posts.size,
            previewBlocks = previewBlocks(posts)
        )
    }

    private fun previewBlocks(posts: List<Post>): List<ContentBlock> =
        posts.flatMap { post ->
            post.blocks.filter { it.type == ContentBlockType.IMAGE || it.type == ContentBlockType.VIDEO }
        }.take(3)

    private fun normalizeCollectionTitle(title: String): String {
        val normalized = title.trim()
        require(normalized.isNotBlank()) { "Collection title is required" }
        return normalized.take(80)
    }

    private fun normalizeCollectionDescription(description: String?): String? =
        description?.trim()?.takeIf(String::isNotBlank)?.take(280)

    private fun requireOwnsCollection(owner: OwnerRef, collection: SavedCollection) {
        require(collection.ownerType == owner.ownerType && collection.ownerId == owner.ownerId) { "Collection not found" }
    }

    private fun requireOwnsContent(actor: OwnerRef, owner: OwnerRef) {
        require(actor.ownerType == owner.ownerType && actor.ownerId == owner.ownerId) { "Content not found" }
    }

    /**
     * Drafts intentionally bypass this gate.  Public posts must contain a
     * human-readable Markdown fragment or a filled creator/media block; titles,
     * tags, technical directives and upload placeholders are not content.
     */
    private fun hasPublishableContent(text: String, blocks: List<ContentBlock>): Boolean =
        publishableMarkdown(text).isNotBlank() || blocks.any(::isPublishableBlock)

    private fun publishableMarkdown(value: String): String =
        value
            .replace(CREATOR_DIRECTIVE, "")
            .replace(WIKI_MEDIA_REFERENCE, "")
            .replace(MARKDOWN_MEDIA_REFERENCE, "")
            .trim()

    private fun isPublishableBlock(block: ContentBlock): Boolean = when (block.type) {
        ContentBlockType.TEXT -> publishableMarkdown(block.data.stringValue("text").orEmpty()).isNotBlank()
        ContentBlockType.IMAGE,
        ContentBlockType.VIDEO,
        ContentBlockType.AUDIO,
        ContentBlockType.FILE -> block.data.hasNonBlankValue("blobId", "url", "src", "previewUrl", "thumbnailUrl", "coverUrl")
        ContentBlockType.GALLERY -> block.data["items"].hasFilledGallery()
        ContentBlockType.LINK_CARD,
        ContentBlockType.TRUSTED_EMBED -> block.data.hasNonBlankValue("url")
        ContentBlockType.CALLOUT -> block.data.hasNonBlankValue("text", "title", "body")
        ContentBlockType.QUOTE -> block.data.hasNonBlankValue("quote", "text")
        ContentBlockType.DIVIDER -> false
        ContentBlockType.CODE -> block.data.hasNonBlankValue("code", "text")
        ContentBlockType.CHECKLIST -> block.data["items"].hasFilledChecklist()
        ContentBlockType.POLL -> block.data.hasNonBlankValue("question") && block.data["options"].hasFilledPollOptions()
    }

    private fun JsonObject.hasNonBlankValue(vararg keys: String): Boolean =
        keys.any { key -> stringValue(key)?.isNotBlank() == true }

    private fun JsonElement?.hasFilledGallery(): Boolean = (this as? JsonArray)?.any { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.isNotBlank() == true
            is JsonObject -> item.hasNonBlankValue("blobId", "url", "src", "previewUrl", "thumbnailUrl", "coverUrl")
            else -> false
        }
    } == true

    private fun JsonElement?.hasFilledChecklist(): Boolean = (this as? JsonArray)?.any { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.isNotBlank() == true
            is JsonObject -> item.hasNonBlankValue("text", "label", "title")
            else -> false
        }
    } == true

    private fun JsonElement?.hasFilledPollOptions(): Boolean = (this as? JsonArray)
        ?.mapNotNull { option ->
            when (option) {
                is JsonPrimitive -> option.contentOrNull
                is JsonObject -> option.stringValue("label")
                else -> null
            }
        }
        ?.count { it.isNotBlank() }
        ?.let { it >= 2 }
        ?: false

    private fun validateContentBlocks(blocks: List<ContentBlock>) {
        require(blocks.map { it.id }.distinct().size == blocks.size) { "Content block identifiers must be unique" }
        blocks.forEach { block ->
            when (block.type) {
                ContentBlockType.POLL -> {
                    val options = pollOptionIds(block)
                    require(options.size in 2..8) { "Poll must contain 2 to 8 options" }
                }
                ContentBlockType.TRUSTED_EMBED -> {
                    val url = block.data["url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Embed URL is required")
                    val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
                        ?: throw IllegalArgumentException("Embed URL is invalid")
                    require(TRUSTED_EMBED_HOSTS.any { host == it || host.endsWith(".$it") }) { "Embed provider is not allowed" }
                }
                else -> Unit
            }
        }
    }

    private fun normalizeContentBlocks(blocks: List<ContentBlock>): List<ContentBlock> = blocks.map { block ->
        if (block.type != ContentBlockType.TRUSTED_EMBED) return@map block
        val raw = block.data["url"]?.jsonPrimitive?.contentOrNull ?: return@map block
        val normalized = normalizeTrustedEmbedUrl(raw)
        block.copy(data = JsonObject(block.data + ("url" to JsonPrimitive(normalized))))
    }

    private fun normalizeTrustedEmbedUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: throw IllegalArgumentException("Embed URL is invalid")
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Embed URL is invalid")
        require(TRUSTED_EMBED_HOSTS.any { host == it || host.endsWith(".$it") }) { "Embed provider is not allowed" }
        val parts = uri.path.trim('/').split('/').filter(String::isNotBlank)
        return when {
            host == "youtu.be" && parts.isNotEmpty() -> "https://www.youtube.com/embed/${parts.first()}"
            host.endsWith("youtube.com") && parts.firstOrNull() == "watch" -> uri.query?.split("&")
                ?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                ?.let { "https://www.youtube.com/embed/$it" } ?: rawUrl.trim()
            host.endsWith("vimeo.com") && host != "player.vimeo.com" && parts.firstOrNull()?.all(Char::isDigit) == true ->
                "https://player.vimeo.com/video/${parts.first()}"
            else -> rawUrl.trim()
        }
    }

    private fun pollOptionIds(block: ContentBlock): List<String> = block.data["options"]?.jsonArray
        ?.mapNotNull { option ->
            when {
                option is JsonPrimitive -> option.contentOrNull
                else -> option.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }
        }
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().take(20)

    private fun normalizeMediaTags(tags: List<String>, required: Boolean): List<String> {
        val normalized = tags
            .map { it.trim().removePrefix("#").lowercase() }
            .filter(String::isNotBlank)
            .distinct()
        require(normalized.size <= MAX_MEDIA_TAGS) { "A media project supports at most $MAX_MEDIA_TAGS tags" }
        require(normalized.all { MEDIA_TAG.matches(it) }) { "Media project tags are invalid" }
        if (required) require(normalized.isNotEmpty()) { "Add 1 to $MAX_MEDIA_TAGS tags before publishing" }
        return normalized
    }

    private fun normalizeMediaAssets(assets: List<PostAsset>, owner: OwnerRef): List<PostAsset> {
        require(assets.size <= MAX_MEDIA_ASSETS) { "A media project supports at most $MAX_MEDIA_ASSETS assets" }
        require(assets.all { it.id.isNotBlank() }) { "Media asset identifier is required" }
        require(assets.map { it.id }.distinct().size == assets.size) { "Media asset identifiers must be unique" }
        val assetIds = assets.map { asset ->
            asset.assetId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Uploaded media assetId is required")
        }
        val verifiedAssets = uploadedAssetVerifier.assets(owner.key(), assetIds)
        val normalized = assets.zip(assetIds).map { (asset, assetId) ->
            // A v2 project has no remote source mode: every visible item is a
            // file owned by MediaStore.  Do not persist any URL/variant sent by
            // the browser; MediaStore is the sole authority for delivery data.
            require(asset.sourceKind == PostAssetSourceKind.UPLOAD) { "Projects support uploaded files only" }
            require(asset.kind in LOCAL_MEDIA_KINDS) { "Projects support image, video, and audio files only" }
            val verified = verifiedAssets[assetId]
                ?: throw IllegalArgumentException("Uploaded media is not available or is not owned by this creator")
            require(verified.sourceKind == PostAssetSourceKind.UPLOAD) { "Media asset must be an uploaded file" }
            require(verified.kind == asset.kind) { "Uploaded media type does not match the asset" }
            verified.copy(
                // The project item id is stable for editor ordering; the media
                // asset id and every delivery URL come from MediaStore.
                id = asset.id,
                assetId = verified.assetId?.trim()?.takeIf(String::isNotBlank) ?: assetId,
                sourceKind = PostAssetSourceKind.UPLOAD,
                url = null,
                provider = null,
                layout = asset.layout
            )
        }
        return repairProjectLayout(normalized)
    }

    private fun validateMediaProject(
        assets: List<PostAsset>,
        tags: List<String>,
        owner: OwnerRef,
        publishing: Boolean
    ) {
        if (!publishing) return
        require(assets.size in 1..MAX_MEDIA_ASSETS) { "A published project needs 1 to $MAX_MEDIA_ASSETS media assets" }
        require(tags.size in 1..MAX_MEDIA_TAGS) { "A published project needs 1 to $MAX_MEDIA_TAGS tags" }
        assets.forEach { asset ->
            require(asset.sourceKind == PostAssetSourceKind.UPLOAD && asset.kind in LOCAL_MEDIA_KINDS) {
                "Projects support uploaded files only"
            }
            require(asset.status == MediaAssetStatus.READY) { "All uploaded media must finish processing" }
            val assetId = requireNotNull(asset.assetId) { "Uploaded media assetId is required" }
            require(uploadedAssetVerifier.status(owner.key(), assetId) == MediaAssetStatus.READY) {
                "Uploaded media is not ready or is not owned by this creator"
            }
        }
        normalizeAndValidateProjectLayout(assets)
    }

    private fun validateMediaPublicationRequest(assets: List<PostAsset>, tags: List<String>, owner: OwnerRef) {
        require(assets.size in 1..MAX_MEDIA_ASSETS) { "A published project needs 1 to $MAX_MEDIA_ASSETS media assets" }
        require(tags.size in 1..MAX_MEDIA_TAGS) { "A published project needs 1 to $MAX_MEDIA_TAGS tags" }
        assets.forEach { asset ->
            val assetId = requireNotNull(asset.assetId) { "Uploaded media assetId is required" }
            require(asset.sourceKind == PostAssetSourceKind.UPLOAD && asset.kind in LOCAL_MEDIA_KINDS) {
                "Projects support uploaded files only"
            }
            val status = uploadedAssetVerifier.status(owner.key(), assetId)
                ?: throw IllegalArgumentException("Uploaded media is not available or is not owned by this creator")
            require(status != MediaAssetStatus.UPLOADING) { "Finish uploading every media file before publication" }
            require(status != MediaAssetStatus.FAILED) {
                "Remove or retry failed media before publication"
            }
        }
        normalizeAndValidateProjectLayout(assets)
    }

    private fun invalidatePendingPublication(draftId: String) {
        val publication = repository.findPostPublication(draftId) ?: return
        if (publication.state in PENDING_PUBLICATION_STATES) {
            val owner = repository.findStoredPost(draftId)?.ownerRef()?.key()
            if (owner != null) publication.processingRunIds.values.forEach { runId ->
                runCatching { mediaAssetProcessor.cancel(owner, runId) }
            }
            repository.savePostPublication(publication.copy(state = PostPublicationState.DRAFT, failureAssetIds = emptyList()))
        }
    }

    private fun mediaDraftDefinitionChanged(before: Post, after: Post): Boolean {
        if (before.allowComments != after.allowComments || before.tags != after.tags) return true
        fun Post.definition() = assets.map { asset ->
            listOf(asset.id, asset.assetId, asset.kind.name, asset.layout?.x, asset.layout?.y, asset.layout?.sizePreset?.name, asset.layout?.layoutVersion)
        }
        return before.definition() != after.definition()
    }

    private fun reconcilePublication(publication: PostPublication): PostPublication {
        if (publication.state !in PENDING_PUBLICATION_STATES) return publication
        if (publication.revisionId != null) return reconcilePostRevision(publication)
        val draft = repository.findStoredPost(publication.draftId) ?: return publication
        if (draft.status != ContentStatus.DRAFT || draft.contentVersion < MEDIA_POST_CONTENT_VERSION) return publication
        val verifiedAssets = draft.assets.mapNotNull { asset -> asset.assetId?.let { uploadedAssetVerifier.asset(draft.ownerRef().key(), it) } }
        val states = verifiedAssets.mapNotNull { asset -> asset.assetId?.let { it to asset.status } }
        val failures = states.filter { it.second == MediaAssetStatus.FAILED }.map { it.first }
        if (failures.isNotEmpty()) {
            return repository.savePostPublication(publication.copy(state = PostPublicationState.NEEDS_MEDIA_ACTION, failureAssetIds = failures))
        }
        if (states.size != draft.assets.size || states.any { it.second != MediaAssetStatus.READY }) {
            val nextState = if (states.any { it.second == MediaAssetStatus.VERIFYING || it.second == MediaAssetStatus.UPLOADING }) {
                PostPublicationState.PENDING_SOURCE
            } else {
                PostPublicationState.PROCESSING_MEDIA
            }
            return if (publication.state == nextState) publication
            else repository.savePostPublication(publication.copy(state = nextState, failureAssetIds = emptyList()))
        }
        val canonicalById = verifiedAssets.associateBy { it.assetId }
        val activatedAt = Instant.now(clock)
        val activePost = draft.copy(
            title = null, text = "", blocks = emptyList(), status = ContentStatus.ACTIVE,
            visibility = Visibility.PUBLIC,
            assets = repairProjectLayout(draft.assets.map { item -> canonicalById[item.assetId]?.copy(id = item.id, layout = item.layout) ?: item }),
            updatedAt = activatedAt
        )
        val activePublication = publication.copy(
            state = PostPublicationState.ACTIVE,
            activatedAt = activatedAt,
            failureAssetIds = emptyList()
        )
        val (activated, savedPublication) = repository.activateMediaPublication(activePost, activePublication)
        indexPost(activated)
        profileUsage.report(activated.ownerRef(), "content", "posts")
        activated.assets.forEach { asset ->
            val assetId = asset.assetId
            val generation = asset.generation
            if (assetId != null && generation != null) runCatching {
                mediaAssetProcessor.releaseSource(activated.ownerRef().key(), assetId, generation)
            }
        }
        return savedPublication
    }

    private fun reconcilePostRevision(publication: PostPublication): PostPublication {
        val revisionId = requireNotNull(publication.revisionId)
        val document = repository.findPostEditorDocument(revisionId) ?: return publication
        val post = repository.findStoredPost(document.postId) ?: return publication
        val verified = document.assets.mapNotNull { item -> item.assetId?.let { id -> uploadedAssetVerifier.asset(post.ownerRef().key(), id)?.copy(id = item.id, layout = item.layout) } }
        val failures = verified.filter { it.sourceStatus == MediaSourceStatus.REJECTED || it.processingStatus == MediaProcessingStatus.FAILED || it.status == MediaAssetStatus.FAILED }
        if (failures.isNotEmpty()) {
            repository.updatePostEditorRevisionState(revisionId, PostRevisionState.NEEDS_ACTION)
            return repository.savePostPublication(publication.copy(state = PostPublicationState.NEEDS_MEDIA_ACTION, failureAssetIds = failures.mapNotNull { it.assetId }))
        }
        if (verified.size != document.assets.size || verified.any { it.deliveryStatus != MediaDeliveryStatus.READY && it.status != MediaAssetStatus.READY }) {
            return publication
        }
        val activatedAt = Instant.now(clock)
        val activePost = post.copy(
            assets = repairProjectLayout(verified), tags = document.tags, allowComments = document.allowComments,
            status = ContentStatus.ACTIVE, updatedAt = activatedAt
        )
        val activePublication = publication.copy(state = PostPublicationState.ACTIVE, activatedAt = activatedAt, failureAssetIds = emptyList())
        val (_, saved) = repository.activateMediaPublication(activePost, activePublication)
        repository.updatePostEditorRevisionState(revisionId, PostRevisionState.ACTIVE)
        indexPost(activePost)
        activePost.assets.forEach { asset -> if (asset.assetId != null && asset.generation != null) runCatching { mediaAssetProcessor.releaseSource(post.ownerRef().key(), asset.assetId, asset.generation) } }
        return saved
    }

    private fun normalizeCommentAttachments(attachments: List<PostAsset>, owner: OwnerRef): List<PostAsset> {
        require(attachments.size <= MAX_COMMENT_ATTACHMENTS) { "A comment supports at most $MAX_COMMENT_ATTACHMENTS attachments" }
        require(attachments.all { it.sourceKind == PostAssetSourceKind.UPLOAD }) { "Comment attachments must be local uploads" }
        require(attachments.all { it.kind == PostAssetKind.IMAGE || it.kind == PostAssetKind.VIDEO }) {
            "Comment attachments must be images or videos"
        }
        val normalized = normalizeMediaAssets(attachments, owner)
        normalized.forEach { asset ->
            require(asset.status == MediaAssetStatus.READY) { "Comment media must finish processing" }
            val assetId = requireNotNull(asset.assetId)
            require(uploadedAssetVerifier.status(owner.key(), assetId) == MediaAssetStatus.READY) {
                "Comment media is not ready or is not owned by this creator"
            }
        }
        return normalized
    }

    private fun normalizeCommentDocument(document: CommentDocumentV1?, fallbackText: String): CommentDocumentV1 {
        val value = document ?: CommentDocumentV1(
            blocks = if (fallbackText.isBlank()) emptyList() else listOf(
                CommentDocumentBlock(
                    type = CommentBlockType.PARAGRAPH,
                    content = listOf(CommentInlineNode(fallbackText))
                )
            )
        )
        require(value.version == 1) { "Comment document version is not supported" }
        require(value.blocks.size <= MAX_COMMENT_BLOCKS) { "A comment supports at most $MAX_COMMENT_BLOCKS blocks" }
        value.blocks.forEach { block ->
            require(block.id.isNotBlank()) { "Comment block id is required" }
            if (block.type == CommentBlockType.HEADING) require(block.level == 2 || block.level == 3) { "Comment headings support levels 2 and 3" }
            if (block.type == CommentBlockType.MEDIA) require(!block.assetId.isNullOrBlank()) { "Comment media block requires assetId" }
            block.content.forEach { inline ->
                inline.marks.forEach { mark ->
                    when (mark.type) {
                        CommentMarkType.LINK -> {
                            val uri = runCatching { java.net.URI(mark.href) }.getOrNull()
                            require(uri?.scheme == "https") { "Comment links must use HTTPS" }
                        }
                        CommentMarkType.MENTION -> require(mark.ownerId?.isNotBlank() == true && mark.ownerType != null) { "Comment mention is invalid" }
                        else -> Unit
                    }
                }
            }
        }
        val text = commentDocumentText(value)
        require(text.length <= MAX_COMMENT_LENGTH) { "Comment text must be at most $MAX_COMMENT_LENGTH characters" }
        return value
    }

    private fun commentDocumentText(document: CommentDocumentV1): String = document.blocks.joinToString("\n") { block ->
        when (block.type) {
            CommentBlockType.DIVIDER, CommentBlockType.MEDIA -> ""
            CommentBlockType.BULLET_LIST, CommentBlockType.ORDERED_LIST, CommentBlockType.CHECKLIST -> block.items.joinToString(" ")
            else -> block.content.joinToString("") { it.text }
        }
    }.trim()

    private fun validateCommentMediaReferences(document: CommentDocumentV1, attachments: List<PostAsset>) {
        val attachmentIds = attachments.mapNotNull { it.assetId }.toSet()
        val references = document.blocks.filter { it.type == CommentBlockType.MEDIA }.mapNotNull { it.assetId }
        require(references.distinct().size == references.size) { "Comment media may be inserted once" }
        require(references.all { it in attachmentIds }) { "Comment media block references an unavailable asset" }
    }


    private fun enrichStory(story: Story, author: AccountUser? = story.author, now: Instant, viewer: OwnerRef? = null): Story =
        withStableStoryUrls(story.copy(
            author = author,
            durationMs = storyDurationMs(story.blocks),
            mediaDurationMs = storyMediaDurationMs(story.blocks),
            closeFriends = story.visibility == Visibility.CLOSE_FRIENDS,
            archived = story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now),
            likeCount = repository.countStoryLikes(story.id),
            likedByViewer = viewer?.let { repository.isStoryLikedBy(story.id, it) } ?: false,
            remainingLifeSeconds = if (story.expiresAt.isAfter(now)) java.time.Duration.between(now, story.expiresAt).seconds else 0
        ))

    private fun normalizeStoryBlock(block: ContentBlock, owner: OwnerRef): ContentBlock {
        if (block.type == ContentBlockType.TEXT) {
            return block.copy(data = JsonObject(block.data + ("text" to JsonPrimitive(block.data.stringValue("text").orEmpty().trim().take(280)))))
        }
        require(block.type in setOf(ContentBlockType.IMAGE, ContentBlockType.VIDEO, ContentBlockType.AUDIO)) {
            "Stories support image, video, audio and caption blocks"
        }
        val assetId = block.data.stringValue("assetId")
        if (assetId != null) {
            val asset = uploadedAssetVerifier.asset(owner.key(), assetId)
                ?: throw IllegalArgumentException("Story media is unavailable or belongs to another creator")
            val expectedKind = when (block.type) {
                ContentBlockType.VIDEO -> PostAssetKind.VIDEO
                ContentBlockType.AUDIO -> PostAssetKind.AUDIO
                else -> PostAssetKind.IMAGE
            }
            require(asset.kind == expectedKind && asset.status == MediaAssetStatus.READY) { "Story media has not finished processing" }
            val generation = asset.generation ?: throw IllegalArgumentException("Story media generation is missing")
            val preferredNames = when (expectedKind) {
                PostAssetKind.IMAGE -> listOf("image-1440", "image-960", "image-2048", "image-480")
                PostAssetKind.VIDEO -> listOf("video-1080")
                PostAssetKind.AUDIO -> listOf("audio")
            }
            val variant = preferredNames.firstNotNullOfOrNull { name -> asset.variants.firstOrNull { it.name == name } }
                ?: throw IllegalArgumentException("Story delivery variant is missing")
            val retained = block.data.filterKeys { it in setOf("caption", "tags", "durationMs", "mediaDurationMs", "trimStartMs", "trimEndMs") }
            val stable = retained + buildMap<String, JsonElement> {
                put("assetId", JsonPrimitive(assetId))
                put("generation", JsonPrimitive(generation))
                put("variantName", JsonPrimitive(requireNotNull(variant.name)))
                put("deliveryContract", JsonPrimitive("STABLE_V2"))
                variant.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
                variant.width?.let { put("width", JsonPrimitive(it)) }
                variant.height?.let { put("height", JsonPrimitive(it)) }
                asset.durationMs?.let { put("mediaDurationMs", JsonPrimitive(it)) }
                asset.variants.firstOrNull { it.name == "poster" }?.name?.let { put("posterVariantName", JsonPrimitive(it)) }
            }
            return normalizeStoryDuration(block.copy(data = JsonObject(stable)))
        }
        // Legacy blob-backed stories remain readable during rollout, but new
        // clients never send a remote URL or server-side multipart upload.
        require(block.data.hasNonBlankValue("blobId", "fileName")) { "Upload story media before publishing" }
        return normalizeStoryDuration(block)
    }

    private fun normalizeStoryDuration(block: ContentBlock): ContentBlock {
        if (block.type != ContentBlockType.VIDEO && block.type != ContentBlockType.AUDIO) return block
        val mediaDuration = block.data.longValue("mediaDurationMs") ?: block.data.longValue("durationMs")
        val cappedDuration = (mediaDuration ?: STORY_VIDEO_MAX_MS).coerceAtMost(STORY_VIDEO_MAX_MS).coerceAtLeast(1_000)
        return block.copy(data = JsonObject(block.data + mapOf(
            "durationMs" to JsonPrimitive(cappedDuration),
            "mediaDurationMs" to JsonPrimitive(mediaDuration ?: cappedDuration),
            "trimStartMs" to JsonPrimitive(0),
            "trimEndMs" to JsonPrimitive(cappedDuration)
        )))
    }

    private fun withStableStoryUrls(story: Story): Story = story.copy(blocks = story.blocks.map { block ->
        val assetId = block.data.stringValue("assetId") ?: return@map block
        val generation = block.data.longValue("generation") ?: return@map block
        val variantName = block.data.stringValue("variantName") ?: return@map block
        if (block.data.stringValue("deliveryContract") != "STABLE_V2") return@map block
        val additions = buildMap<String, JsonElement> {
            put("url", JsonPrimitive("/content-media/assets/$assetId/$generation/$variantName"))
            block.data.stringValue("posterVariantName")?.let { poster ->
                put("posterUrl", JsonPrimitive("/content-media/assets/$assetId/$generation/$poster"))
            }
        }
        block.copy(data = JsonObject(block.data + additions))
    })

    private fun storyDurationMs(blocks: List<ContentBlock>): Long =
        blocks.firstNotNullOfOrNull { block ->
            when (block.type) {
                ContentBlockType.VIDEO, ContentBlockType.AUDIO ->
                    (block.data.longValue("durationMs") ?: block.data.longValue("mediaDurationMs") ?: STORY_VIDEO_MAX_MS)
                        .coerceAtMost(STORY_VIDEO_MAX_MS)
                        .coerceAtLeast(1_000)
                ContentBlockType.IMAGE -> STORY_IMAGE_DURATION_MS
                ContentBlockType.TEXT, ContentBlockType.FILE,
                ContentBlockType.GALLERY, ContentBlockType.LINK_CARD, ContentBlockType.CALLOUT,
                ContentBlockType.QUOTE, ContentBlockType.DIVIDER, ContentBlockType.CODE,
                ContentBlockType.CHECKLIST, ContentBlockType.POLL, ContentBlockType.TRUSTED_EMBED -> null
            }
        } ?: STORY_IMAGE_DURATION_MS

    private fun storyMediaDurationMs(blocks: List<ContentBlock>): Long? =
        blocks.firstNotNullOfOrNull { block ->
            if (block.type == ContentBlockType.VIDEO || block.type == ContentBlockType.AUDIO) {
                block.data.longValue("mediaDurationMs") ?: block.data.longValue("durationMs")
            } else {
                null
            }
        }

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun ContentBlock.mediaSource(): String? =
        data.mediaSource()

    private fun JsonObject.mediaSource(): String? {
        val direct = listOf("previewUrl", "thumbnailUrl", "coverUrl", "url", "src")
            .firstNotNullOfOrNull { key -> stringValue(key)?.takeIf(String::isNotBlank) }
        if (direct != null) return direct
        val blobId = stringValue("blobId")?.takeIf(String::isNotBlank) ?: return null
        return "/content-media/$blobId"
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

private fun SessionUser.asAccountUser(): AccountUser =
    AccountUser(id = id, ownerType = OwnerType.USER, username = username, displayName = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { username }, firstName = firstName, lastName = lastName, avatarUrl = avatarUrl)

private fun AccountOwner.ref(): OwnerRef = OwnerRef(ownerType = ownerType, ownerId = id)

    private fun withViewerState(post: Post, viewer: OwnerRef?): Post =
        post.copy(
            likeCount = repository.countPostLikes(post.id),
            likedByViewer = viewer?.let { repository.isPostLikedBy(post.id, it) } ?: false
        )

    private fun withAuthor(post: Post, authorResolver: (String) -> AccountUser?): Post =
        withStableAssetUrls(post.copy(author = post.author ?: authorResolver(post.ownerKey())))

    private fun withStableAssetUrls(post: Post): Post = post.copy(assets = post.assets.map { asset ->
        val assetId = asset.assetId
        if (asset.deliveryContract != "STABLE_V2" || assetId.isNullOrBlank()) return@map asset
        val generation = asset.generation
        val variants = asset.variants.map { variant ->
            val name = variant.name
            if (generation == null || name.isNullOrBlank()) variant.copy(url = "")
            else variant.copy(url = "/content-media/assets/$assetId/$generation/$name")
        }
        asset.copy(
            url = if (post.status == ContentStatus.DRAFT) "/content-media/assets/$assetId/source" else null,
            variants = variants,
            posterUrl = variants.firstOrNull { it.name == "poster" }?.url,
            waveformUrl = variants.firstOrNull { it.name == "waveform" }?.url
        )
    })

    private fun withEditorAssetUrls(document: PostEditorDocument): PostEditorDocument = document.copy(assets = document.assets.map { asset ->
        val id = asset.assetId ?: return@map asset
        val generation = asset.generation
        val variants = asset.variants.map { variant ->
            val name = variant.name
            if (generation == null || name.isNullOrBlank()) variant else variant.copy(url = "/content-media/assets/$id/$generation/$name")
        }
        asset.copy(url = "/content-media/assets/$id/source", variants = variants,
            posterUrl = variants.firstOrNull { it.name == "poster" }?.url,
            waveformUrl = variants.firstOrNull { it.name == "waveform" }?.url)
    })

    private fun withoutEditorTags(post: Post): Post = post.copy(tags = emptyList())

    /** Tags are returned only to the active owner editing their own project. */
    private fun revealEditorTagsToOwner(post: Post, viewer: OwnerRef?): Post =
        if (viewer?.ownerType == post.ownerType && viewer.ownerId == post.ownerId) post else withoutEditorTags(post)

    private fun withCommentViewerState(
        comment: Comment,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser?
    ): Comment =
        comment.copy(
            author = comment.author ?: authorResolver(comment.ownerKey()),
            likeCount = repository.countCommentLikes(comment.id),
            likedByViewer = viewer?.let { repository.isCommentLikedBy(comment.id, it) } ?: false,
            blocks = comment.blocks.ifEmpty { if (comment.text.isBlank()) emptyList() else listOf(textBlock(comment.text)) }
        )

    private fun normalizeSearchTypes(types: List<String>): Set<String> {
        val allowed = setOf("posts", "collections", "tags")
        val normalized = types.map { it.trim().lowercase() }.filter { it in allowed }.toSet()
        return normalized.ifEmpty { allowed }
    }

    private fun matchesSearchFilters(post: Post, input: ContentSearchInput): Boolean {
        val tags = input.tags.map { it.trim().removePrefix("#").lowercase() }.filter(String::isNotBlank).toSet()
        if (tags.isNotEmpty() && post.tags.none { it.lowercase() in tags }) return false
        val author = input.author?.trim()?.removePrefix("@")?.lowercase()?.takeIf(String::isNotBlank)
        if (author != null) {
            val authorText = listOf(post.author?.username, post.author?.displayName, post.ownerId, post.authorId)
                .filterNotNull()
                .joinToString(" ")
                .lowercase()
            if (!authorText.contains(author)) return false
        }
        return isWithinSearchDate(post.createdAt, input)
    }

    private fun matchesSearchFilters(collection: SavedCollection, input: ContentSearchInput): Boolean =
        isWithinSearchDate(collection.updatedAt, input)

    private fun isWithinSearchDate(value: Instant, input: ContentSearchInput): Boolean {
        val from = input.dateFrom?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = input.dateTo?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (from != null && value.isBefore(from)) return false
        if (to != null && value.isAfter(to)) return false
        return true
    }

    private fun postSearchItem(post: Post, score: Double, snippet: String?): ContentSearchItem =
        ContentSearchItem(
            type = "POST",
            id = post.id,
            title = post.title ?: post.text.lineSequence().firstOrNull()?.take(80),
            snippet = snippet ?: post.text.take(180),
            owner = post.author,
            url = "/p/${post.id}",
            score = score,
            createdAt = post.createdAt.toString(),
            postId = post.id,
            tags = publicSearchTags(post),
            meta = mapOf(
                "likeCount" to post.likeCount.toString(),
                "visibility" to post.visibility.name
            ),
            typeLabel = "Post",
            thumbnailUrl = postThumbnail(post),
            highlights = listOfNotNull(snippet?.takeIf(String::isNotBlank), post.text.take(180).takeIf(String::isNotBlank)).distinct()
        )

    private fun collectionSearchItem(
        collection: SavedCollection,
        owner: AccountUser?,
        score: Double,
        snippet: String?
    ): ContentSearchItem =
        ContentSearchItem(
            type = "COLLECTION",
            id = collection.id,
            title = collection.title,
            snippet = snippet ?: collection.description,
            owner = owner,
            url = owner?.let { "/${if (it.ownerType == OwnerType.ORGANIZATION) "o" else "u"}/${it.username}/collections/${collection.id}" }
                ?: "/collections/${collection.id}",
            score = score,
            createdAt = collection.updatedAt.toString(),
            meta = mapOf(
                "itemCount" to collection.itemCount.toString(),
                "visibility" to collection.visibility.name,
                "ownerType" to collection.ownerType.name,
                "ownerId" to collection.ownerId
            ),
            typeLabel = "Collection",
            thumbnailUrl = collection.cover?.mediaSource(),
            highlights = listOfNotNull(snippet?.takeIf(String::isNotBlank), collection.description?.takeIf(String::isNotBlank)).distinct()
        )

    private fun publicSearchTags(post: Post): List<String> =
        if (post.contentVersion >= MEDIA_POST_CONTENT_VERSION) emptyList() else post.tags

    private fun postThumbnail(post: Post): String? =
        post.assets.firstNotNullOfOrNull { asset -> asset.posterUrl ?: asset.variants.firstOrNull()?.url ?: asset.url }
            ?: post.blocks.firstNotNullOfOrNull { it.mediaSource() }

    private fun searchFacets(items: List<ContentSearchItem>, input: ContentSearchInput): List<ContentSearchFacet> {
        val selectedTypes = input.types.map { it.trim().lowercase() }.toSet()
        val selectedTags = input.tags.map { it.trim().removePrefix("#").lowercase() }.toSet()
        val selectedAuthor = input.author?.trim()?.removePrefix("@")?.lowercase()

        val typeFacets = listOf(
            typeFacet("posts", "Posts", items.count { it.type == "POST" }, selectedTypes),
            typeFacet("collections", "Collections", items.count { it.type == "COLLECTION" }, selectedTypes),
            typeFacet("tags", "Tags", items.count { it.type == "TAG" }, selectedTypes)
        )

        val tagFacets = items.asSequence()
            .flatMap { it.tags.asSequence() }
            .map { it.trim().removePrefix("#").lowercase() }
            .filter(String::isNotBlank)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .map { (tag, count) ->
                ContentSearchFacet("tag", tag, "#$tag", count, selectedTags.contains(tag))
            }

        val ownerFacets = items.asSequence()
            .mapNotNull { item ->
                item.owner?.let { owner ->
                    val value = owner.id
                    val label = owner.displayName ?: "@${owner.username}"
                    value to label
                }
            }
            .groupBy({ it.first }, { it.second })
            .map { (ownerId, labels) ->
                ContentSearchFacet("owner", ownerId, labels.first(), labels.size, selectedAuthor == ownerId.lowercase() || selectedAuthor == labels.first().lowercase())
            }
            .sortedWith(compareByDescending<ContentSearchFacet> { it.count }.thenBy { it.label.lowercase() })
            .take(10)

        val dateFacets = dateRangeFacets(items)

        val providerFacets = listOf(ContentSearchFacet("provider", "content", "Content", items.size))

        return (typeFacets + providerFacets + tagFacets + ownerFacets + dateFacets)
            .filter { it.count > 0 }
    }

    private fun typeFacet(value: String, label: String, count: Int, selected: Set<String>): ContentSearchFacet =
        ContentSearchFacet("type", value, label, count, selected.contains(value))

    private fun dateRangeFacets(items: List<ContentSearchItem>): List<ContentSearchFacet> {
        val now = Instant.now(clock)
        val parsed = items.mapNotNull { item -> item.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } }
        return listOf(
            ContentSearchFacet("dateRange", "day", "Past day", parsed.count { it.isAfter(now.minusSeconds(24 * 60 * 60)) }),
            ContentSearchFacet("dateRange", "week", "Past week", parsed.count { it.isAfter(now.minusSeconds(7 * 24 * 60 * 60)) }),
            ContentSearchFacet("dateRange", "month", "Past month", parsed.count { it.isAfter(now.minusSeconds(30 * 24 * 60 * 60)) })
        ).filter { it.count > 0 }
    }

    private fun sortSearchItems(items: List<ContentSearchItem>, sort: String): List<ContentSearchItem> =
        when (sort.lowercase()) {
            "new" -> items.sortedByDescending { it.createdAt.orEmpty() }
            "popular" -> items.sortedWith(compareByDescending<ContentSearchItem> { it.meta["likeCount"]?.toLongOrNull() ?: 0L }.thenByDescending { it.score })
            else -> items.sortedWith(compareByDescending<ContentSearchItem> { it.score }.thenByDescending { it.createdAt.orEmpty() })
        }

    /** Rebuilds a privacy-safe aggregate. Search receives neither comment ids
     * nor authors, and therefore can only return the parent post. */
    private fun reindexPostDiscussion(postId: String) {
        val post = repository.findPost(postId) ?: return
        val active = repository.listCommentsForPost(postId, COMMENT_DISCUSSION_SCAN_LIMIT)
            .asSequence()
            .filter { it.status == ContentStatus.ACTIVE }
            .map { it to repository.countCommentLikes(it.id) }
            .sortedWith(
                compareByDescending<Pair<Comment, Long>> { it.first.pinnedAt != null }
                    .thenByDescending { it.first.parentId == null }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.createdAt }
            )
            .take(MAX_DISCUSSION_COMMENTS)
            .toList()
        val discussion = buildString {
            for ((comment, likes) in active) {
                val text = commentDocumentText(comment.document ?: CommentDocumentV1()).ifBlank { comment.text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_DISCUSSION_COMMENT_CHARS)
                if (text.isBlank()) continue
                val weight = when {
                    comment.pinnedAt != null -> 3
                    comment.parentId == null || likes > 0 -> 2
                    else -> 1
                }
                repeat(weight) {
                    if (length >= MAX_DISCUSSION_CHARS) return@buildString
                    if (isNotEmpty()) append('\n')
                    append(text.take(MAX_DISCUSSION_CHARS - length))
                }
            }
        }
        val previous = repository.findPostSearchProjection(postId)
        val revision = maxOf(Instant.now(clock).toEpochMilli(), (previous?.revision ?: 0L) + 1L)
        repository.savePostSearchProjection(
            PostSearchProjection(postId, discussion, active.size, revision, Instant.now(clock))
        )
        searchEvents.postUpsert(post, discussion, revision)
    }

    private fun indexPost(post: Post) {
        val projection = repository.findPostSearchProjection(post.id)
        searchEvents.postUpsert(post, projection?.discussion.orEmpty(), maxOf(post.updatedAt.toEpochMilli(), projection?.revision ?: 0L))
    }

    companion object {
        private val PENDING_PUBLICATION_STATES = setOf(
            PostPublicationState.PENDING_SOURCE,
            PostPublicationState.PROCESSING_MEDIA,
            PostPublicationState.PENDING_MEDIA,
            PostPublicationState.NEEDS_MEDIA_ACTION
        )
        const val MEDIA_POST_CONTENT_VERSION = 3
        const val MAX_MEDIA_ASSETS = 12
        const val MAX_MEDIA_TAGS = 5
        const val MAX_COMMENT_LENGTH = 4_000
        const val MAX_COMMENT_BLOCKS = 30
        const val MAX_COMMENT_ATTACHMENTS = 4
        private const val COMMENT_THREAD_SCAN_LIMIT = 10_000
        private const val COMMENT_DISCUSSION_SCAN_LIMIT = 10_000
        private const val MAX_DISCUSSION_COMMENTS = 100
        private const val MAX_DISCUSSION_COMMENT_CHARS = 1_000
        private const val MAX_DISCUSSION_CHARS = 32_000
        const val STORY_VIDEO_MAX_MS = 60_000L
        const val STORY_IMAGE_DURATION_MS = 5_000L
        private const val FEED_CELL_COLUMNS = 3
        private const val EXPLORATION_INTERVAL = 8
        private const val MIX_CONSTELLATION_KEY = "mix"
        // Must match the Content canvas spatial chunk, so camera requests and
        // server-side world ranges address exactly the same area.
        private const val WORLD_CHUNK_SIZE = 1_440.0
        private const val CONSTELLATION_MIN_DISTANCE = 520
        private const val CONSTELLATION_MAX_DISTANCE = 760
        private const val CONSTELLATION_ANCHOR_CLEARANCE = 480.0
        private const val CONSTELLATION_ANCHOR_PROBES = 96
        private const val POST_PROTECTED_GAP = 24.0
        private const val POST_STEP_MIN = 300
        private const val POST_STEP_MAX = 420
        private const val POST_PROBE_DISTANCE = 32
        private const val POST_PLACEMENT_PROBES = 2_048
        private val GOLDEN_ANGLE = PI * (3.0 - kotlin.math.sqrt(5.0))
        private val CONSTELLATION_KEY_SEPARATOR = Regex("[^\\p{L}\\p{N}_-]+")
        private val CREATOR_DIRECTIVE = Regex(
            """(?im)^:::onix\s+(?:GALLERY|LINK_CARD|CALLOUT|QUOTE|DIVIDER|CODE|CHECKLIST|POLL|TRUSTED_EMBED)(?:\s+\{.*\})?\s*$"""
        )
        private val WIKI_MEDIA_REFERENCE = Regex("""!\[\[media:[^|\]]+(?:\|[^\]]*)?]]""")
        private val MARKDOWN_MEDIA_REFERENCE = Regex("""\[[^\]]*]\(media:[^)]+\)""")
        private val MEDIA_TAG = Regex("^[\\p{L}\\p{N}_-]{1,48}$")
        private val LOCAL_MEDIA_KINDS = setOf(PostAssetKind.IMAGE, PostAssetKind.VIDEO, PostAssetKind.AUDIO)
        private val TRUSTED_EMBED_HOSTS = setOf("youtube.com", "youtu.be", "vimeo.com", "spotify.com", "soundcloud.com", "figma.com")
    }
}

private fun Post.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Post.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Comment.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Comment.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Story.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Story.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Story.isArchivedAt(now: Instant): Boolean =
    status == ContentStatus.ARCHIVED || !expiresAt.isAfter(now)

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun SavedCollection.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(OwnerType.valueOf(parts[0]), parts[1]) else OwnerRef(OwnerType.USER, this)
}

private fun AccountVisibility.viewerRef(): OwnerRef? =
    viewerId?.let { OwnerRef(viewerType, it) }
