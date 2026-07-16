package com.onix.content.api

import com.onix.content.account.AccountClient
import com.onix.content.account.AccountUnauthorized
import com.onix.content.config.AppConfig
import com.onix.content.domain.*
import com.onix.content.media.MediaClient
import com.onix.content.media.MediaUnavailable
import com.onix.content.media.UploadedMedia
import com.onix.content.service.ContentService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.unlim.profile.grpc.v1.UserActivityType
import com.unlim.profile.grpc.v1.OwnerAction
import java.time.Duration
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun Application.registerRoutes(config: AppConfig, account: AccountClient, media: MediaClient, content: ContentService) {
    install(CallLogging)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(20)
    }
    install(CORS) {
        config.allowedOrigins.forEach { origin ->
            val url = Url(origin)
            allowHost(url.hostWithPort, schemes = listOf(url.protocol.name))
        }
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Onix-Redirect")
        allowHeader("X-Profile-Redirect")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }
    install(StatusPages) {
        exception<AccountUnauthorized> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, GraphQlResponse(errors = listOf(GraphQlError("AUTH_REQUIRED"))))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, GraphQlResponse(errors = listOf(GraphQlError(cause.message ?: "Invalid request"))))
        }
        exception<MediaUnavailable> { call, cause ->
            call.respond(HttpStatusCode.ServiceUnavailable, GraphQlResponse(errors = listOf(GraphQlError(cause.message ?: "Media unavailable"))))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled content request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, GraphQlResponse(errors = listOf(GraphQlError("Service unavailable"))))
        }
    }

    routing {
        get("/livez") { call.respond(mapOf("status" to "UP")) }
        get("/health") { call.respond(mapOf("status" to "UP")) }
        get("/readyz") {
            call.respond(
                mapOf(
                    "status" to "UP",
                    "accountGrpc" to config.accountGrpcUrl,
                    "mediaGrpc" to (config.mediaGrpcUrl ?: "not-configured"),
                    "searchGrpc" to (config.searchGrpcUrl ?: "not-configured")
                )
            )
        }
        get("/metrics") {
            call.respondText(
                "# HELP content_service_up Content service process is up.\n# TYPE content_service_up gauge\ncontent_service_up 1\n",
                ContentType.Text.Plain
            )
        }

        get("/content-media/{blobId}") {
            call.respondMediaRedirect(media, account, content)
        }

        head("/content-media/{blobId}") {
            call.respondMediaRedirect(media, account, content)
        }

        get("/content-media/assets/{assetId}/source") {
            call.respondStableAssetRedirect(media, account, content, source = true)
        }
        head("/content-media/assets/{assetId}/source") {
            call.respondStableAssetRedirect(media, account, content, source = true)
        }
        get("/content-media/assets/{assetId}/{generation}/{variantName}") {
            call.respondStableAssetRedirect(media, account, content, source = false)
        }
        head("/content-media/assets/{assetId}/{generation}/{variantName}") {
            call.respondStableAssetRedirect(media, account, content, source = false)
        }

        get("/internal/profile/users/{ownerId}/content") {
            val token = call.accessToken() ?: throw AccountUnauthorized()
            val actor = account.getCurrentActor(token)
            account.activateContentForUser(actor.user.id, token)
            val ownerId = call.parameters["ownerId"] ?: throw IllegalArgumentException("ownerId is required")
            val visibility = account.visibility(ownerId, actor.user.id, token)
            call.respond(
                content.profileContent(
                    ownerId = ownerId,
                    visibility = visibility,
                    postLimit = call.request.queryParameters["postLimit"]?.toIntOrNull() ?: 12,
                    storyLimit = call.request.queryParameters["storyLimit"]?.toIntOrNull() ?: 8,
                    authorResolver = authorResolver(actor.user, account, token),
                    visibilityResolver = visibilityResolver(actor, account, token)
                )
            )
        }

        get("/internal/profile/owners/{ownerType}/{ownerId}/content") {
            val token = call.accessToken() ?: throw AccountUnauthorized()
            val actor = account.getCurrentActor(token)
            account.activateContentForUser(actor.user.id, token)
            val ownerType = OwnerType.valueOf(call.parameters["ownerType"] ?: throw IllegalArgumentException("ownerType is required"))
            val ownerId = call.parameters["ownerId"] ?: throw IllegalArgumentException("ownerId is required")
            val owner = OwnerRef(ownerType, ownerId)
            val viewer = OwnerRef(actor.activeOwner.ownerType, actor.activeOwner.id)
            val visibility = account.ownerVisibility(owner, viewer, token)
            call.respond(
                content.profileContent(
                    ownerId = ownerId,
                    visibility = visibility,
                    postLimit = call.request.queryParameters["postLimit"]?.toIntOrNull() ?: 12,
                    storyLimit = call.request.queryParameters["storyLimit"]?.toIntOrNull() ?: 8,
                    authorResolver = authorResolver(actor.user, account, token),
                    visibilityResolver = visibilityResolver(actor, account, token)
                )
            )
        }

        post("/graphql") {
            if (call.request.isMultipart()) {
                val token = call.accessToken() ?: throw AccountUnauthorized()
                val actor = account.getCurrentActor(token)
                account.activateContentForUser(actor.user.id, token)
                call.respond(handleMultipartUpload(call, actor, media, content, account, token))
                return@post
            }
            val request = json.decodeFromString(GraphQlRequest.serializer(), call.receiveText())
            val token = call.accessToken()
            val actor = token?.let {
                account.getCurrentActor(it).also { current -> account.activateContentForUser(current.user.id, it) }
            }
            call.respond(dispatchGraphQl(request, actor, account, media, content, token))
        }

        webSocket("/subscriptions") {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "Subscriptions are reserved for feed updates"))
        }
    }
}

private suspend fun handleMultipartUpload(call: ApplicationCall, actor: CurrentActor, media: MediaClient, content: ContentService, account: AccountClient, token: String): GraphQlResponse {
    val parts = call.receiveMultipart()
    var operations: GraphQlRequest? = null
    val uploads = mutableListOf<UploadedMedia>()
    parts.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> if (part.name == "operations") {
                operations = json.decodeFromString(GraphQlRequest.serializer(), part.value)
            }
            is PartData.FileItem -> {
                if (operations?.operationName == "createStory") {
                    throw IllegalArgumentException("Stories require direct Media upload before createStory")
                }
                val fileName = part.originalFileName ?: "upload"
                val mimeType = part.headers[HttpHeaders.ContentType]?.takeIf(String::isNotBlank) ?: "application/octet-stream"
                val bytes = part.streamProvider().readBytes()
                uploads.add(media.upload(fileName, mimeType, bytes))
            }
            else -> Unit
        }
        part.dispose()
    }
    val request = enrichUploads(operations ?: GraphQlRequest(operationName = "createPost", variables = buildJsonObject {
        put("input", buildJsonObject {
            // A raw multipart request must become actual media blocks, not a
            // text placeholder that can bypass the publication-content gate.
            put("blocks", JsonArray(emptyList()))
        })
    }), uploads)
    val response = dispatchGraphQl(request, actor, account, media, content, token)
    referenceUploads(media, content, response, uploads)
    return response
}

private fun enrichUploads(request: GraphQlRequest, uploads: List<UploadedMedia>): GraphQlRequest {
    if (uploads.isEmpty()) return request
    val variables = request.variables ?: return request
    val input = variables["input"] as? JsonObject ?: return request
    val blocks = input["blocks"]?.jsonArray ?: JsonArray(emptyList())
    val enrichedBlocks = enrichUploadBlocks(blocks, uploads)
    val enrichedInput = JsonObject(input + ("blocks" to JsonArray(enrichedBlocks)))
    return request.copy(variables = JsonObject(variables + ("input" to enrichedInput)))
}

internal fun enrichUploadBlocks(blocks: JsonArray, uploads: List<UploadedMedia>): List<JsonElement> {
    var uploadIndex = 0
    val enriched = blocks.map { element ->
        val block = element.jsonObject
        val type = block["type"]?.jsonPrimitive?.contentOrNull
        val data = block["data"]?.jsonObject.orEmpty()
        val existingBlobId = data["blobId"]?.jsonPrimitive?.contentOrNull
        if (type !in UPLOADABLE_BLOCK_TYPES || !existingBlobId.isNullOrBlank() || uploadIndex >= uploads.size) return@map element
        val upload = uploads[uploadIndex++]
        JsonObject(block + ("data" to JsonObject(data + mapOf(
            "blobId" to JsonPrimitive(upload.blobId),
            "fileName" to JsonPrimitive(upload.fileName),
            "mimeType" to JsonPrimitive(upload.mimeType),
            "size" to JsonPrimitive(upload.size)
        ))))
    }.toMutableList()
    uploads.drop(uploadIndex).forEach { upload ->
        enriched += JsonObject(mapOf(
            "id" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
            "type" to JsonPrimitive(uploadBlockType(upload).name),
            "data" to JsonObject(mapOf(
                "blobId" to JsonPrimitive(upload.blobId),
                "fileName" to JsonPrimitive(upload.fileName),
                "mimeType" to JsonPrimitive(upload.mimeType),
                "size" to JsonPrimitive(upload.size)
            ))
        ))
    }
    return enriched
}

private fun uploadBlockType(upload: UploadedMedia): ContentBlockType = when {
    upload.mimeType.startsWith("image/") -> ContentBlockType.IMAGE
    upload.mimeType.startsWith("video/") -> ContentBlockType.VIDEO
    upload.mimeType.startsWith("audio/") -> ContentBlockType.AUDIO
    else -> ContentBlockType.FILE
}

private val UPLOADABLE_BLOCK_TYPES = setOf(
    ContentBlockType.IMAGE.name,
    ContentBlockType.VIDEO.name,
    ContentBlockType.AUDIO.name,
    ContentBlockType.FILE.name
)

private fun referenceUploads(media: MediaClient, content: ContentService, response: GraphQlResponse, uploads: List<UploadedMedia>) {
    if (uploads.isEmpty()) return
    val data = response.data ?: return
    val created = data["createPost"]?.jsonObject ?: data["savePostDraft"]?.jsonObject ?: data["createStory"]?.jsonObject ?: data["createComment"]?.jsonObject ?: return
    val ownerType = when {
        data.containsKey("createPost") || data.containsKey("savePostDraft") -> "post"
        data.containsKey("createStory") -> "story"
        else -> "comment"
    }
    val ownerId = created["id"]?.jsonPrimitive?.contentOrNull ?: return
    uploads.forEach {
        media.createReference(it.blobId, ownerType, ownerId)
        content.recordMediaReference(ownerType, ownerId, it.blobId)
    }
}

private fun mediaErrorStatus(error: MediaUnavailable): HttpStatusCode {
    val message = error.message.orEmpty().lowercase()
    return when {
        "not fully processed" in message || "did not complete" in message -> HttpStatusCode.Conflict
        "not found" in message || "not owned" in message || "forbidden" in message -> HttpStatusCode.NotFound
        "not configured" in message -> HttpStatusCode.ServiceUnavailable
        else -> HttpStatusCode.ServiceUnavailable
    }
}

private suspend fun ApplicationCall.respondMediaRedirect(media: MediaClient, account: AccountClient, content: ContentService) {
    val token = accessToken()
    val actor = token?.let { account.getCurrentActor(it) }
    val blobId = parameters["blobId"] ?: throw IllegalArgumentException("blobId is required")
    val canView = content.canViewMedia(blobId, visibilityResolver(actor, account, token))
    if (!canView) {
        if (token == null) throw AccountUnauthorized()
        return respond(HttpStatusCode.NotFound, GraphQlResponse(errors = listOf(GraphQlError("Media not found"))))
    }
    val downloadUrl = try {
        media.downloadUrl(blobId)
    } catch (error: MediaUnavailable) {
        return respond(mediaErrorStatus(error), GraphQlResponse(errors = listOf(GraphQlError(error.message ?: "Media unavailable"))))
    }
    respondRedirect(downloadUrl, permanent = false)
}

private suspend fun ApplicationCall.respondStableAssetRedirect(
    media: MediaClient,
    account: AccountClient,
    content: ContentService,
    source: Boolean
) {
    val token = accessToken()
    val actor = token?.let { account.getCurrentActor(it) }
    val assetId = parameters["assetId"] ?: throw IllegalArgumentException("assetId is required")
    val generation = if (source) null else parameters["generation"]?.toLongOrNull()
        ?: throw IllegalArgumentException("generation is required")
    val owner = content.resolveStableAssetOwner(
        assetId = assetId,
        generation = generation,
        source = source,
        viewer = actor?.activeOwner?.ref(),
        visibilityResolver = visibilityResolver(actor, account, token)
    ) ?: run {
        if (source && token == null) throw AccountUnauthorized()
        return respond(HttpStatusCode.NotFound, GraphQlResponse(errors = listOf(GraphQlError("Media not found"))))
    }
    val url = try {
        if (source) media.resolveSource(owner, assetId)
        else media.resolveDelivery(owner, assetId, requireNotNull(generation), parameters["variantName"].orEmpty())
    } catch (error: MediaUnavailable) {
        return respond(mediaErrorStatus(error), GraphQlResponse(errors = listOf(GraphQlError(error.message ?: "Media unavailable"))))
    }
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    response.headers.append(HttpHeaders.Vary, "Authorization, Cookie")
    respondRedirect(url, permanent = false)
}

private fun dispatchGraphQl(
    request: GraphQlRequest,
    actor: CurrentActor?,
    account: AccountClient?,
    media: MediaClient,
    content: ContentService,
    token: String?
): GraphQlResponse {
    val operation = request.operationName ?: operationFromQuery(request.query)
    val variables = request.variables ?: JsonObject(emptyMap())
    val activeOwner = actor?.activeOwner?.ref()
    val viewer = activeOwner ?: ANONYMOUS_VIEWER
    val data = when (operation) {
        "currentActor" -> buildJsonObject {
            put("currentActor", json.encodeToJsonElement(requireActor(actor)))
        }
        "initMediaAssetUpload" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("initMediaAssetUpload", json.encodeToJsonElement(
                media.initAssetUpload(currentActor.activeOwner.ref().key(), decodeInput(variables, InitAssetUploadInput.serializer()))
            ))
        }
        "completeMediaAssetUpload" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("completeMediaAssetUpload", json.encodeToJsonElement(
                media.completeAssetUpload(currentActor.activeOwner.ref().key(), decodeInput(variables, CompleteAssetUploadInput.serializer()))
            ))
        }
        "mediaAsset" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val assetId = variables["assetId"]?.jsonPrimitive?.contentOrNull
                ?: variables["id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("assetId is required")
            put("mediaAsset", json.encodeToJsonElement(media.getAssetForOwner(currentActor.activeOwner.ref().key(), assetId)))
        }
        "retryMediaAssetProcessing" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val assetId = variables["assetId"]?.jsonPrimitive?.contentOrNull
                ?: variables["id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("assetId is required")
            put("retryMediaAssetProcessing", json.encodeToJsonElement(media.retryAssetProcessing(currentActor.activeOwner.ref().key(), assetId)))
        }
        "createPost" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val post = content.createPost(currentActor, decodeInput(variables, CreatePostInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.post:${post.id}:published", currentActor.user.id, UserActivityType.POST_PUBLISHED, "post", post.id, token)
            }
            put("createPost", json.encodeToJsonElement(post))
        }
        "savePostDraft" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("savePostDraft", json.encodeToJsonElement(content.savePostDraft(currentActor, decodeInput(variables, SavePostDraftInput.serializer()))))
        }
        "createPostDraft" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("createPostDraft", json.encodeToJsonElement(content.createPostDraft(currentActor)))
        }
        "beginPostEdit" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            put("beginPostEdit", json.encodeToJsonElement(content.beginPostEdit(currentActor, postId)))
        }
        "postEditorDocument" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val revisionId = variables["revisionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("revisionId is required")
            put("postEditorDocument", json.encodeToJsonElement(content.postEditorDocument(currentActor, revisionId)))
        }
        "editorMediaAssets" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val assetIds = variables["assetIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: throw IllegalArgumentException("assetIds are required")
            put("editorMediaAssets", json.encodeToJsonElement(content.editorMediaAssets(currentActor, assetIds)))
        }
        "savePostEditorDocument" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("savePostEditorDocument", json.encodeToJsonElement(content.savePostEditorDocument(
                currentActor,
                decodeInput(variables, SavePostEditorDocumentInput.serializer())
            )))
        }
        "requestPostRevisionPublication" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val revisionId = variables["revisionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("revisionId is required")
            val idempotencyKey = variables["idempotencyKey"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("idempotencyKey is required")
            put("requestPostRevisionPublication", json.encodeToJsonElement(
                content.requestPostRevisionPublication(currentActor, revisionId, idempotencyKey)
            ))
        }
        "postDrafts" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            put("postDrafts", json.encodeToJsonElement(content.listPostDrafts(currentActor, limit)))
        }
        "postDraft" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val draftId = variables["draftId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("draftId is required")
            put("postDraft", json.encodeToJsonElement(content.postDraft(currentActor, draftId)))
        }
        "publishPostDraft" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val draftId = variables["draftId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("draftId is required")
            val post = content.publishPostDraft(currentActor, draftId)
            if (account != null && token != null) {
                account.publishUserActivity("content.post:${post.id}:published", currentActor.user.id, UserActivityType.POST_PUBLISHED, "post", post.id, token)
            }
            put("publishPostDraft", json.encodeToJsonElement(post))
        }
        "requestPostPublication" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            put("requestPostPublication", json.encodeToJsonElement(content.requestPostPublication(
                currentActor,
                decodeInput(variables, RequestPostPublicationInput.serializer())
            )))
        }
        "postPublication" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val draftId = variables["draftId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("draftId is required")
            put("postPublication", json.encodeToJsonElement(content.postPublication(currentActor, draftId)))
        }
        "cancelPostPublication" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val draftId = variables["draftId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("draftId is required")
            put("cancelPostPublication", json.encodeToJsonElement(content.cancelPostPublication(currentActor, draftId)))
        }
        "createStory" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.CREATE_CONTENT)
            val story = content.createStory(currentActor, decodeInput(variables, CreateStoryInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.story:${story.id}:published", currentActor.user.id, UserActivityType.STORY_PUBLISHED, "story", story.id, token)
            }
            put("createStory", json.encodeToJsonElement(story))
        }
        "createComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            val comment = content.createComment(currentActor, decodeInput(variables, CreateCommentInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.comment:${comment.id}:created", currentActor.user.id, UserActivityType.POST_COMMENT, "comment", comment.id, token)
            }
            put("createComment", json.encodeToJsonElement(comment))
        }
        "updatePost" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("updatePost", json.encodeToJsonElement(content.updatePost(currentActor, decodeInput(variables, UpdatePostInput.serializer()))))
        }
        "deletePost" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            content.deletePost(currentActor, id)
            put("deletePost", JsonPrimitive(true))
        }
        "updateComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("updateComment", json.encodeToJsonElement(content.updateComment(currentActor, decodeInput(variables, UpdateCommentInput.serializer()))))
        }
        "deleteComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            content.deleteComment(currentActor, id)
            put("deleteComment", JsonPrimitive(true))
        }
        "pinComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            val pinned = variables["pinned"]?.jsonPrimitive?.booleanOrNull ?: true
            put("pinComment", json.encodeToJsonElement(content.pinComment(currentActor, commentId, pinned)))
        }
        "hideComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            put("hideComment", json.encodeToJsonElement(content.hideComment(currentActor, commentId)))
        }
        "reportComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("reportComment", JsonPrimitive(content.reportComment(currentActor, decodeInput(variables, ReportCommentInput.serializer()))))
        }
        "deleteStory" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            content.deleteStory(currentActor, id)
            put("deleteStory", JsonPrimitive(true))
        }
        "feed" -> buildJsonObject {
            val tags = variables["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 20
            put("feed", json.encodeToJsonElement(content.feed(viewer, tags, limit, authorResolver(actor?.user, account, token), visibilityResolver(actor, account, token))))
        }
        "recommendationFeed" -> buildJsonObject {
            val input = decodeInput(variables, RecommendationFeedInput.serializer())
            val graph = if (account != null && token != null && activeOwner != null) account.ownerSocialGraph(activeOwner, token) else AccountSocialGraph()
            put("recommendationFeed", json.encodeToJsonElement(content.recommendationFeed(
                viewer = viewer,
                input = input,
                socialGraph = graph,
                authorResolver = authorResolver(actor?.user, account, token),
                visibilityResolver = visibilityResolver(actor, account, token)
            )))
        }
        "post" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("post", json.encodeToJsonElement(content.post(id, activeOwner, visibilityResolver(actor, account, token), authorResolver(actor?.user, account, token))))
        }
        "comment" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("comment", json.encodeToJsonElement(content.comment(id, activeOwner, visibilityResolver(actor, account, token), authorResolver(actor?.user, account, token))))
        }
        "story" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("story", json.encodeToJsonElement(content.story(id, activeOwner, visibilityResolver(actor, account, token))))
        }
        "storiesFeed" -> buildJsonObject {
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val resolver = authorResolver(actor?.user, account, token)
            val visibilityResolver = visibilityResolver(actor, account, token)
            put("storiesFeed", json.encodeToJsonElement(content.storiesFeed(viewer, limit, resolver, visibilityResolver)))
        }
        "storyGroup" -> buildJsonObject {
            val authorId = variables["authorId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("authorId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: OwnerType.USER
            val startStoryId = variables["startStoryId"]?.jsonPrimitive?.contentOrNull
            val archive = variables["archive"]?.jsonPrimitive?.booleanOrNull ?: false
            put("storyGroup", json.encodeToJsonElement(content.storyGroup(
                viewer = viewer,
                authorId = authorId,
                ownerType = ownerType,
                startStoryId = startStoryId,
                authorResolver = authorResolver(actor?.user, account, token),
                visibilityResolver = visibilityResolver(actor, account, token),
                archive = archive
            )))
        }
        "storyArchive" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: activeOwner?.ownerId ?: throw IllegalArgumentException("ownerId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: activeOwner?.ownerType ?: OwnerType.USER
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val cursor = variables["cursor"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
            val ownerKey = OwnerRef(ownerType, ownerId).key()
            val owner = authorResolver(actor?.user, account, token)(ownerKey)
            val visibility = visibilityResolver(actor, account, token)(ownerKey)
            put("storyArchive", json.encodeToJsonElement(content.storyArchive(ownerId, visibility, owner, limit, cursor)))
        }
        "storyArchivePeriods" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: activeOwner?.ownerId ?: throw IllegalArgumentException("ownerId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: activeOwner?.ownerType ?: OwnerType.USER
            val ownerKey = OwnerRef(ownerType, ownerId).key()
            val visibility = visibilityResolver(actor, account, token)(ownerKey)
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 60
            put("storyArchivePeriods", json.encodeToJsonElement(content.storyArchivePeriods(ownerId, visibility, limit)))
        }
        "comments" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 100
            put("comments", json.encodeToJsonElement(content.comments(postId, limit, activeOwner, visibilityResolver(actor, account, token), authorResolver(actor?.user, account, token))))
        }
        "commentThread" -> buildJsonObject {
            val input = decodeInput(variables, CommentThreadInput.serializer())
            put("commentThread", json.encodeToJsonElement(content.commentThread(input, activeOwner, visibilityResolver(actor, account, token), authorResolver(actor?.user, account, token))))
        }
        "profileContent" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: activeOwner?.ownerId ?: throw IllegalArgumentException("ownerId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: activeOwner?.ownerType ?: OwnerType.USER
            val owner = OwnerRef(ownerType, ownerId)
            val visibility = visibilityResolver(actor, account, token)(owner.key())
            put("profileContent", json.encodeToJsonElement(content.profileContent(ownerId, visibility, 12, 8, authorResolver(actor?.user, account, token), visibilityResolver(actor, account, token))))
        }
        "collections" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: activeOwner?.ownerId ?: throw IllegalArgumentException("ownerId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: activeOwner?.ownerType ?: OwnerType.USER
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 80
            val owner = OwnerRef(ownerType, ownerId)
            val visibility = visibilityResolver(actor, account, token)(owner.key())
            put("collections", json.encodeToJsonElement(content.collections(owner, visibility, limit, visibilityResolver(actor, account, token))))
        }
        "collection" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 200
            put("collection", json.encodeToJsonElement(content.collection(
                id = id,
                viewer = activeOwner ?: ANONYMOUS_VIEWER,
                visibilityResolver = visibilityResolver(actor, account, token),
                authorResolver = authorResolver(actor?.user, account, token),
                limit = limit
            )))
        }
        "createCollection" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("createCollection", json.encodeToJsonElement(content.createCollection(currentActor, decodeInput(variables, CreateCollectionInput.serializer()))))
        }
        "updateCollection" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("updateCollection", json.encodeToJsonElement(content.updateCollection(currentActor, decodeInput(variables, UpdateCollectionInput.serializer()))))
        }
        "deleteCollection" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            content.deleteCollection(currentActor, id)
            put("deleteCollection", JsonPrimitive(true))
        }
        "postCollections" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            put("postCollections", json.encodeToJsonElement(content.postCollections(currentActor, postId)))
        }
        "setPostCollections" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("setPostCollections", json.encodeToJsonElement(content.setPostCollections(
                actor = currentActor,
                input = decodeInput(variables, SetPostCollectionsInput.serializer()),
                visibilityResolver = visibilityResolver(actor, account, token)
            )))
        }
        "addPostToCollection" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val collectionId = variables["collectionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("collectionId is required")
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("addPostToCollection", json.encodeToJsonElement(content.addPostToCollection(currentActor, collectionId, postId, visibilityResolver(actor, account, token))))
        }
        "removePostFromCollection" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val collectionId = variables["collectionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("collectionId is required")
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("removePostFromCollection", json.encodeToJsonElement(content.removePostFromCollection(currentActor, collectionId, postId)))
        }
        "likePost" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("likePost", json.encodeToJsonElement(content.likePost(currentActor, postId)))
        }
        "unlikePost" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("unlikePost", json.encodeToJsonElement(content.unlikePost(currentActor, postId)))
        }
        "votePoll" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("votePoll", json.encodeToJsonElement(content.votePoll(currentActor, decodeInput(variables, PollVoteInput.serializer()))))
        }
        "closePoll" -> buildJsonObject {
            val currentActor = requireActor(actor)
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val blockId = variables["blockId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("blockId is required")
            put("closePoll", json.encodeToJsonElement(content.closePoll(currentActor, postId, blockId)))
        }
        "likeStory" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("likeStory", json.encodeToJsonElement(content.likeStory(currentActor, storyId)))
        }
        "unlikeStory" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("unlikeStory", json.encodeToJsonElement(content.unlikeStory(currentActor, storyId)))
        }
        "likeComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("likeComment", json.encodeToJsonElement(content.likeComment(currentActor, commentId)))
        }
        "unlikeComment" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("unlikeComment", json.encodeToJsonElement(content.unlikeComment(currentActor, commentId)))
        }
        "recordView" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val durationMs = variables["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put(operation, JsonPrimitive(content.recordPostView(currentActor, postId, durationMs)))
        }
        "recordStoryView" -> buildJsonObject {
            val currentActor = requireActor(actor)
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, currentActor, OwnerAction.ACT_AS_OWNER)
            put("recordStoryView", JsonPrimitive(content.recordStoryView(currentActor, storyId)))
        }
        else -> throw IllegalArgumentException("Unsupported GraphQL operation: ${operation ?: "unknown"}")
    }
    return GraphQlResponse(data = data)
}

private fun operationFromQuery(query: String?): String? {
    if (query.isNullOrBlank()) return null
    return listOf("initMediaAssetUpload", "completeMediaAssetUpload", "mediaAsset", "editorMediaAssets", "retryMediaAssetProcessing", "currentActor", "createPostDraft", "beginPostEdit", "postEditorDocument", "savePostEditorDocument", "requestPostRevisionPublication", "savePostDraft", "postDrafts", "postDraft", "publishPostDraft", "requestPostPublication", "postPublication", "cancelPostPublication", "createPost", "updatePost", "deletePost", "createStory", "deleteStory", "createComment", "updateComment", "deleteComment", "pinComment", "hideComment", "reportComment", "commentThread", "comment", "votePoll", "closePoll", "recommendationFeed", "postCollections", "setPostCollections", "addPostToCollection", "removePostFromCollection", "createCollection", "updateCollection", "deleteCollection", "profileContent", "storyGroup", "storyArchivePeriods", "storyArchive", "storiesFeed", "collections", "collection", "comments", "likePost", "unlikePost", "likeStory", "unlikeStory", "likeComment", "unlikeComment", "recordStoryView", "recordView", "feed", "post", "story")
        .firstOrNull { query.contains(it) }
}

private fun authorResolver(user: SessionUser?, account: AccountClient?, token: String?): (String) -> AccountUser? {
    val cache = mutableMapOf<String, AccountUser?>()
    return { ownerKey ->
        cache.getOrPut(ownerKey) {
            val owner = ownerKey.toOwnerRef()
            if (user != null && owner.ownerType == OwnerType.USER && owner.ownerId == user.id) {
                AccountUser(
                    id = user.id,
                    ownerType = OwnerType.USER,
                    username = user.username,
                    displayName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.username },
                    firstName = user.firstName,
                    lastName = user.lastName,
                    avatarUrl = user.avatarUrl
                )
            } else if (account != null) {
                account.getOwner(owner, token)
            } else {
                null
            }
        }
    }
}

private fun requireOwnerAction(account: AccountClient?, token: String?, actor: CurrentActor, action: OwnerAction) {
    if (actor.activeOwner.ownerType == OwnerType.USER && actor.activeOwner.id == actor.user.id) return
    if (account == null || token == null) throw AccountUnauthorized()
    val allowed = account.authorize(OwnerRef(actor.activeOwner.ownerType, actor.activeOwner.id), action, token)
    if (!allowed) throw IllegalArgumentException("Active owner is not allowed to perform this action")
}

private fun requireActor(actor: CurrentActor?): CurrentActor =
    actor ?: throw AccountUnauthorized()

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) {
        OwnerRef(OwnerType.valueOf(parts[0]), parts[1])
    } else {
        OwnerRef(OwnerType.USER, this)
    }
}

private fun visibilityResolver(actor: CurrentActor?, account: AccountClient?, token: String?): (String) -> AccountVisibility {
    val cache = mutableMapOf<String, AccountVisibility>()
    return { ownerKey ->
        cache.getOrPut(ownerKey) {
            val owner = ownerKey.toOwnerRef()
            val viewer = actor?.activeOwner?.ref()
            if (account != null) {
                account.ownerVisibility(owner, viewer, token)
            } else {
                AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer?.ownerId, viewerType = viewer?.ownerType ?: OwnerType.USER)
            }
        }
    }
}

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun AccountOwner.ref(): OwnerRef =
    OwnerRef(ownerType, id)

private val ANONYMOUS_VIEWER = OwnerRef(OwnerType.USER, "00000000-0000-0000-0000-000000000000")

private fun <T> decodeInput(variables: JsonObject, serializer: kotlinx.serialization.KSerializer<T>): T {
    val element = variables["input"] ?: variables
    return json.decodeFromJsonElement(serializer, element)
}

private fun ApplicationRequest.isMultipart(): Boolean =
    contentType().match(ContentType.MultiPart.FormData)

private fun ApplicationCall.accessToken(): String? {
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(" ")
        ?.takeIf(String::isNotBlank)
    return bearer
        ?: request.cookies["__Host-access_token"]
        ?: request.cookies["access_token"]
}

@Serializable
data class GraphQlRequest(
    val query: String? = null,
    val operationName: String? = null,
    val variables: JsonObject? = null
)

@Serializable
data class GraphQlError(val message: String)

@Serializable
data class GraphQlResponse(
    val data: JsonObject? = null,
    val errors: List<GraphQlError> = emptyList()
)
