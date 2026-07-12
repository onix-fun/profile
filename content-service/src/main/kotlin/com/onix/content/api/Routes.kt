package com.onix.content.api

import com.onix.content.account.AccountClient
import com.onix.content.account.AccountUnauthorized
import com.onix.content.config.AppConfig
import com.onix.content.domain.*
import com.onix.content.media.MediaClient
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
        allowHeader("X-Profile-Redirect")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }
    install(StatusPages) {
        exception<AccountUnauthorized> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, GraphQlResponse(errors = listOf(GraphQlError("AUTH_REQUIRED"))))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, GraphQlResponse(errors = listOf(GraphQlError(cause.message ?: "Invalid request"))))
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
                    "mediaApi" to config.mediaBaseUrl,
                    "searchApi" to config.searchBaseUrl
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
            call.accessToken() ?: throw AccountUnauthorized()
            val blobId = call.parameters["blobId"] ?: throw IllegalArgumentException("blobId is required")
            val downloaded = media.download(blobId)
            val contentType = runCatching { ContentType.parse(downloaded.mimeType) }.getOrDefault(ContentType.Application.OctetStream)
            call.respondBytes(downloaded.bytes, contentType)
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
            val actor = account.getCurrentActor(token ?: throw AccountUnauthorized())
            account.activateContentForUser(actor.user.id, token)
            call.respond(dispatchGraphQl(request, actor, account, content, token))
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
            put("text", "Uploaded ${uploads.size} file(s)")
            put("tags", JsonArray(listOf(JsonPrimitive("upload"))))
        })
    }), uploads)
    val response = dispatchGraphQl(request, actor, account, content, token)
    referenceUploads(media, response, uploads)
    return response
}

private fun enrichUploads(request: GraphQlRequest, uploads: List<UploadedMedia>): GraphQlRequest {
    if (uploads.isEmpty()) return request
    val variables = request.variables ?: return request
    val input = variables["input"] as? JsonObject ?: return request
    val blocks = input["blocks"]?.jsonArray ?: return request
    val enrichedBlocks = enrichUploadBlocks(blocks, uploads)
    val enrichedInput = JsonObject(input + ("blocks" to JsonArray(enrichedBlocks)))
    return request.copy(variables = JsonObject(variables + ("input" to enrichedInput)))
}

internal fun enrichUploadBlocks(blocks: JsonArray, uploads: List<UploadedMedia>): List<JsonElement> {
    var uploadIndex = 0
    return blocks.map { element ->
        val block = element.jsonObject
        val type = block["type"]?.jsonPrimitive?.contentOrNull
        if (type == "TEXT" || uploadIndex >= uploads.size) return@map element
        val upload = uploads[uploadIndex++]
        val data = block["data"]?.jsonObject.orEmpty()
        JsonObject(block + ("data" to JsonObject(data + mapOf(
            "blobId" to JsonPrimitive(upload.blobId),
            "fileName" to JsonPrimitive(upload.fileName),
            "mimeType" to JsonPrimitive(upload.mimeType),
            "size" to JsonPrimitive(upload.size)
        ))))
    }
}

private fun referenceUploads(media: MediaClient, response: GraphQlResponse, uploads: List<UploadedMedia>) {
    if (uploads.isEmpty()) return
    val data = response.data ?: return
    val created = data["createPost"]?.jsonObject ?: data["createStory"]?.jsonObject ?: data["createComment"]?.jsonObject ?: return
    val ownerType = when {
        data.containsKey("createPost") -> "post"
        data.containsKey("createStory") -> "story"
        else -> "comment"
    }
    val ownerId = created["id"]?.jsonPrimitive?.contentOrNull ?: return
    uploads.forEach { media.createReference(it.blobId, ownerType, ownerId) }
}

private fun dispatchGraphQl(
    request: GraphQlRequest,
    actor: CurrentActor,
    account: AccountClient?,
    content: ContentService,
    token: String?
): GraphQlResponse {
    val operation = request.operationName ?: operationFromQuery(request.query)
    val variables = request.variables ?: JsonObject(emptyMap())
    val activeOwner = OwnerRef(actor.activeOwner.ownerType, actor.activeOwner.id)
    val data = when (operation) {
        "currentActor" -> buildJsonObject {
            put("currentActor", json.encodeToJsonElement(actor))
        }
        "createPost" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.CREATE_CONTENT)
            val post = content.createPost(actor, decodeInput(variables, CreatePostInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.post:${post.id}:published", actor.user.id, UserActivityType.POST_PUBLISHED, "post", post.id, token)
            }
            put("createPost", json.encodeToJsonElement(post))
        }
        "createStory" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.CREATE_CONTENT)
            val story = content.createStory(actor, decodeInput(variables, CreateStoryInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.story:${story.id}:published", actor.user.id, UserActivityType.STORY_PUBLISHED, "story", story.id, token)
            }
            put("createStory", json.encodeToJsonElement(story))
        }
        "createComment" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            val comment = content.createComment(actor, decodeInput(variables, CreateCommentInput.serializer()))
            if (account != null && token != null) {
                account.publishUserActivity("content.comment:${comment.id}:created", actor.user.id, UserActivityType.POST_COMMENT, "comment", comment.id, token)
            }
            put("createComment", json.encodeToJsonElement(comment))
        }
        "feed" -> buildJsonObject {
            val tags = variables["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 20
            put("feed", json.encodeToJsonElement(content.feed(activeOwner, tags, limit, authorResolver(actor.user, account, token))))
        }
        "recommendationFeed" -> buildJsonObject {
            val input = decodeInput(variables, RecommendationFeedInput.serializer())
            val graph = if (account != null && token != null) account.ownerSocialGraph(activeOwner, token) else AccountSocialGraph()
            put("recommendationFeed", json.encodeToJsonElement(content.recommendationFeed(
                viewer = activeOwner,
                input = input,
                socialGraph = graph,
                authorResolver = authorResolver(actor.user, account, token)
            )))
        }
        "post" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("post", json.encodeToJsonElement(content.post(id, activeOwner, authorResolver(actor.user, account, token))))
        }
        "story" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("story", json.encodeToJsonElement(content.story(id, activeOwner)))
        }
        "storiesFeed" -> buildJsonObject {
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val resolver = authorResolver(actor.user, account, token)
            val visibilityResolver = visibilityResolver(actor, account, token)
            put("storiesFeed", json.encodeToJsonElement(content.storiesFeed(activeOwner, limit, resolver, visibilityResolver)))
        }
        "storyGroup" -> buildJsonObject {
            val authorId = variables["authorId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("authorId is required")
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: OwnerType.USER
            val startStoryId = variables["startStoryId"]?.jsonPrimitive?.contentOrNull
            val archive = variables["archive"]?.jsonPrimitive?.booleanOrNull ?: false
            put("storyGroup", json.encodeToJsonElement(content.storyGroup(
                viewer = activeOwner,
                authorId = authorId,
                ownerType = ownerType,
                startStoryId = startStoryId,
                authorResolver = authorResolver(actor.user, account, token),
                visibilityResolver = visibilityResolver(actor, account, token),
                archive = archive
            )))
        }
        "storyArchive" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: actor.activeOwner.id
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: actor.activeOwner.ownerType
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val cursor = variables["cursor"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
            val ownerKey = OwnerRef(ownerType, ownerId).key()
            val owner = authorResolver(actor.user, account, token)(ownerKey)
            val visibility = visibilityResolver(actor, account, token)(ownerKey)
            put("storyArchive", json.encodeToJsonElement(content.storyArchive(ownerId, visibility, owner, limit, cursor)))
        }
        "comments" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 100
            put("comments", json.encodeToJsonElement(content.comments(postId, limit, activeOwner, authorResolver(actor.user, account, token))))
        }
        "profileContent" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: actor.activeOwner.id
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: actor.activeOwner.ownerType
            val owner = OwnerRef(ownerType, ownerId)
            val visibility = if (account != null && token != null) account.ownerVisibility(owner, activeOwner, token)
            else AccountVisibility(ownerId = ownerId, ownerType = ownerType, viewerId = activeOwner.ownerId, viewerType = activeOwner.ownerType)
            put("profileContent", json.encodeToJsonElement(content.profileContent(ownerId, visibility, 12, 8, authorResolver(actor.user, account, token), visibilityResolver(actor, account, token))))
        }
        "collections" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: actor.activeOwner.id
            val ownerType = variables["ownerType"]?.jsonPrimitive?.contentOrNull?.let { OwnerType.valueOf(it) } ?: actor.activeOwner.ownerType
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 80
            val owner = OwnerRef(ownerType, ownerId)
            val visibility = if (account != null && token != null) account.ownerVisibility(owner, activeOwner, token)
            else AccountVisibility(ownerId = ownerId, ownerType = ownerType, viewerId = activeOwner.ownerId, viewerType = activeOwner.ownerType)
            put("collections", json.encodeToJsonElement(content.collections(owner, visibility, limit, visibilityResolver(actor, account, token))))
        }
        "collection" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 200
            put("collection", json.encodeToJsonElement(content.collection(
                id = id,
                viewer = activeOwner,
                visibilityResolver = visibilityResolver(actor, account, token),
                authorResolver = authorResolver(actor.user, account, token),
                limit = limit
            )))
        }
        "createCollection" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("createCollection", json.encodeToJsonElement(content.createCollection(actor, decodeInput(variables, CreateCollectionInput.serializer()))))
        }
        "updateCollection" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("updateCollection", json.encodeToJsonElement(content.updateCollection(actor, decodeInput(variables, UpdateCollectionInput.serializer()))))
        }
        "deleteCollection" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            content.deleteCollection(actor, id)
            put("deleteCollection", JsonPrimitive(true))
        }
        "postCollections" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            put("postCollections", json.encodeToJsonElement(content.postCollections(actor, postId)))
        }
        "setPostCollections" -> buildJsonObject {
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("setPostCollections", json.encodeToJsonElement(content.setPostCollections(
                actor = actor,
                input = decodeInput(variables, SetPostCollectionsInput.serializer()),
                visibilityResolver = visibilityResolver(actor, account, token)
            )))
        }
        "addPostToCollection" -> buildJsonObject {
            val collectionId = variables["collectionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("collectionId is required")
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("addPostToCollection", json.encodeToJsonElement(content.addPostToCollection(actor, collectionId, postId, visibilityResolver(actor, account, token))))
        }
        "removePostFromCollection" -> buildJsonObject {
            val collectionId = variables["collectionId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("collectionId is required")
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("removePostFromCollection", json.encodeToJsonElement(content.removePostFromCollection(actor, collectionId, postId)))
        }
        "likePost" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("likePost", json.encodeToJsonElement(content.likePost(actor, postId)))
        }
        "unlikePost" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("unlikePost", json.encodeToJsonElement(content.unlikePost(actor, postId)))
        }
        "likeStory" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("likeStory", json.encodeToJsonElement(content.likeStory(actor, storyId)))
        }
        "unlikeStory" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("unlikeStory", json.encodeToJsonElement(content.unlikeStory(actor, storyId)))
        }
        "likeComment" -> buildJsonObject {
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("likeComment", json.encodeToJsonElement(content.likeComment(actor, commentId)))
        }
        "unlikeComment" -> buildJsonObject {
            val commentId = variables["commentId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("commentId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("unlikeComment", json.encodeToJsonElement(content.unlikeComment(actor, commentId)))
        }
        "recordView" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val durationMs = variables["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put(operation, JsonPrimitive(content.recordPostView(actor, postId, durationMs)))
        }
        "recordStoryView" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            requireOwnerAction(account, token, actor, OwnerAction.ACT_AS_OWNER)
            put("recordStoryView", JsonPrimitive(content.recordStoryView(actor, storyId)))
        }
        else -> throw IllegalArgumentException("Unsupported GraphQL operation: ${operation ?: "unknown"}")
    }
    return GraphQlResponse(data = data)
}

private fun operationFromQuery(query: String?): String? {
    if (query.isNullOrBlank()) return null
    return listOf("currentActor", "createPost", "createStory", "createComment", "recommendationFeed", "postCollections", "setPostCollections", "addPostToCollection", "removePostFromCollection", "createCollection", "updateCollection", "deleteCollection", "profileContent", "storyGroup", "storyArchive", "storiesFeed", "collections", "collection", "comments", "likePost", "unlikePost", "likeStory", "unlikeStory", "likeComment", "unlikeComment", "recordStoryView", "recordView", "feed", "post", "story")
        .firstOrNull { query.contains(it) }
}

private fun authorResolver(user: SessionUser, account: AccountClient?, token: String?): (String) -> AccountUser? {
    val cache = mutableMapOf<String, AccountUser?>()
    return { ownerKey ->
        cache.getOrPut(ownerKey) {
            val owner = ownerKey.toOwnerRef()
            if (owner.ownerType == OwnerType.USER && owner.ownerId == user.id) {
                AccountUser(
                    id = user.id,
                    ownerType = OwnerType.USER,
                    username = user.username,
                    displayName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.username },
                    firstName = user.firstName,
                    lastName = user.lastName,
                    avatarUrl = user.avatarUrl
                )
            } else if (account != null && token != null) {
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

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) {
        OwnerRef(OwnerType.valueOf(parts[0]), parts[1])
    } else {
        OwnerRef(OwnerType.USER, this)
    }
}

private fun visibilityResolver(actor: CurrentActor, account: AccountClient?, token: String?): (String) -> AccountVisibility {
    val cache = mutableMapOf<String, AccountVisibility>()
    return { ownerKey ->
        cache.getOrPut(ownerKey) {
            val owner = ownerKey.toOwnerRef()
            val viewer = OwnerRef(actor.activeOwner.ownerType, actor.activeOwner.id)
            if (account != null && token != null) {
                account.ownerVisibility(owner, viewer, token)
            } else {
                AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType)
            }
        }
    }
}

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

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
