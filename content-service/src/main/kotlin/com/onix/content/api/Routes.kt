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
                    "accountApi" to config.accountBaseUrl,
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
            val viewer = account.getMe(token)
            val ownerId = call.parameters["ownerId"] ?: throw IllegalArgumentException("ownerId is required")
            val visibility = account.visibility(ownerId, viewer.id, token)
            call.respond(
                content.profileContent(
                    ownerId = ownerId,
                    visibility = visibility,
                    postLimit = call.request.queryParameters["postLimit"]?.toIntOrNull() ?: 12,
                    storyLimit = call.request.queryParameters["storyLimit"]?.toIntOrNull() ?: 8
                )
            )
        }

        post("/graphql") {
            if (call.request.isMultipart()) {
                val token = call.accessToken() ?: throw AccountUnauthorized()
                val user = account.getMe(token)
                call.respond(handleMultipartUpload(call, user, media, content))
                return@post
            }
            val request = json.decodeFromString(GraphQlRequest.serializer(), call.receiveText())
            val token = call.accessToken()
            val user = account.getMe(token ?: throw AccountUnauthorized())
            call.respond(dispatchGraphQl(request, user, account, content, token))
        }

        webSocket("/subscriptions") {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "Subscriptions are reserved for feed updates"))
        }
    }
}

private suspend fun handleMultipartUpload(call: ApplicationCall, user: SessionUser, media: MediaClient, content: ContentService): GraphQlResponse {
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
    val response = dispatchGraphQl(request, user, null, content, null)
    referenceUploads(media, response, uploads)
    return response
}

private fun enrichUploads(request: GraphQlRequest, uploads: List<UploadedMedia>): GraphQlRequest {
    if (uploads.isEmpty()) return request
    val variables = request.variables ?: return request
    val input = variables["input"] as? JsonObject ?: return request
    val blocks = input["blocks"]?.jsonArray ?: return request
    var uploadIndex = 0
    val enrichedBlocks = blocks.map { element ->
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
    val enrichedInput = JsonObject(input + ("blocks" to JsonArray(enrichedBlocks)))
    return request.copy(variables = JsonObject(variables + ("input" to enrichedInput)))
}

private fun referenceUploads(media: MediaClient, response: GraphQlResponse, uploads: List<UploadedMedia>) {
    if (uploads.isEmpty()) return
    val data = response.data ?: return
    val created = data["createPost"]?.jsonObject ?: data["createStory"]?.jsonObject ?: return
    val ownerType = if (data.containsKey("createPost")) "post" else "story"
    val ownerId = created["id"]?.jsonPrimitive?.contentOrNull ?: return
    uploads.forEach { media.createReference(it.blobId, ownerType, ownerId) }
}

private fun dispatchGraphQl(
    request: GraphQlRequest,
    user: SessionUser,
    account: AccountClient?,
    content: ContentService,
    token: String?
): GraphQlResponse {
    val operation = request.operationName ?: operationFromQuery(request.query)
    val variables = request.variables ?: JsonObject(emptyMap())
    val data = when (operation) {
        "createPost" -> buildJsonObject {
            put("createPost", json.encodeToJsonElement(content.createPost(user, decodeInput(variables, CreatePostInput.serializer()))))
        }
        "createStory" -> buildJsonObject {
            put("createStory", json.encodeToJsonElement(content.createStory(user, decodeInput(variables, CreateStoryInput.serializer()))))
        }
        "createComment" -> buildJsonObject {
            put("createComment", json.encodeToJsonElement(content.createComment(user, decodeInput(variables, CreateCommentInput.serializer()))))
        }
        "feed" -> buildJsonObject {
            val tags = variables["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 20
            put("feed", json.encodeToJsonElement(content.feed(user.id, tags, limit)))
        }
        "post" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("post", json.encodeToJsonElement(content.post(id, user.id)))
        }
        "story" -> buildJsonObject {
            val id = variables["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("id is required")
            put("story", json.encodeToJsonElement(content.story(id, user.id)))
        }
        "storiesFeed" -> buildJsonObject {
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val resolver = authorResolver(user, account, token)
            val visibilityResolver = visibilityResolver(user, account, token)
            put("storiesFeed", json.encodeToJsonElement(content.storiesFeed(user.id, limit, resolver, visibilityResolver)))
        }
        "storyGroup" -> buildJsonObject {
            val authorId = variables["authorId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("authorId is required")
            val startStoryId = variables["startStoryId"]?.jsonPrimitive?.contentOrNull
            val archive = variables["archive"]?.jsonPrimitive?.booleanOrNull ?: false
            put("storyGroup", json.encodeToJsonElement(content.storyGroup(
                viewerId = user.id,
                authorId = authorId,
                startStoryId = startStoryId,
                authorResolver = authorResolver(user, account, token),
                visibilityResolver = visibilityResolver(user, account, token),
                archive = archive
            )))
        }
        "storyArchive" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: user.id
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val cursor = variables["cursor"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
            val owner = authorResolver(user, account, token)(ownerId)
            val visibility = visibilityResolver(user, account, token)(ownerId)
            put("storyArchive", json.encodeToJsonElement(content.storyArchive(ownerId, visibility, owner, limit, cursor)))
        }
        "comments" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            val limit = variables["limit"]?.jsonPrimitive?.intOrNull ?: 100
            put("comments", json.encodeToJsonElement(content.comments(postId, limit)))
        }
        "profileContent" -> buildJsonObject {
            val ownerId = variables["ownerId"]?.jsonPrimitive?.content ?: user.id
            val visibility = if (account != null && token != null) account.visibility(ownerId, user.id, token)
            else AccountVisibility(ownerId = ownerId, viewerId = user.id)
            put("profileContent", json.encodeToJsonElement(content.profileContent(ownerId, visibility, 12, 8)))
        }
        "likePost" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            put("likePost", json.encodeToJsonElement(content.likePost(user, postId)))
        }
        "unlikePost" -> buildJsonObject {
            val postId = variables["postId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("postId is required")
            put("unlikePost", json.encodeToJsonElement(content.unlikePost(user, postId)))
        }
        "likeStory" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            put("likeStory", json.encodeToJsonElement(content.likeStory(user, storyId)))
        }
        "unlikeStory" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            put("unlikeStory", json.encodeToJsonElement(content.unlikeStory(user, storyId)))
        }
        "recordView" -> buildJsonObject {
            put(operation, true)
        }
        "recordStoryView" -> buildJsonObject {
            val storyId = variables["storyId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("storyId is required")
            put("recordStoryView", JsonPrimitive(content.recordStoryView(user, storyId)))
        }
        else -> throw IllegalArgumentException("Unsupported GraphQL operation: ${operation ?: "unknown"}")
    }
    return GraphQlResponse(data = data)
}

private fun operationFromQuery(query: String?): String? {
    if (query.isNullOrBlank()) return null
    return listOf("createPost", "createStory", "createComment", "feed", "post", "storyGroup", "storyArchive", "story", "storiesFeed", "comments", "profileContent", "likePost", "unlikePost", "likeStory", "unlikeStory", "recordView", "recordStoryView")
        .firstOrNull { query.contains(it) }
}

private fun authorResolver(user: SessionUser, account: AccountClient?, token: String?): (String) -> AccountUser? {
    val cache = mutableMapOf<String, AccountUser?>()
    return { authorId ->
        cache.getOrPut(authorId) {
            if (authorId == user.id) {
                AccountUser(
                    id = user.id,
                    username = user.username,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    avatarUrl = user.avatarUrl
                )
            } else if (account != null && token != null) {
                account.getUser(authorId, token)
            } else {
                null
            }
        }
    }
}

private fun visibilityResolver(user: SessionUser, account: AccountClient?, token: String?): (String) -> AccountVisibility {
    val cache = mutableMapOf<String, AccountVisibility>()
    return { ownerId ->
        cache.getOrPut(ownerId) {
            if (account != null && token != null) account.visibility(ownerId, user.id, token)
            else AccountVisibility(ownerId = ownerId, viewerId = user.id)
        }
    }
}

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
