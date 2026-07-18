package com.onix.profile.api

import com.onix.profile.account.*
import com.onix.profile.config.AppConfig
import com.onix.profile.content.ContentClient
import com.onix.profile.service.ProfileSearchInput
import com.onix.profile.domain.*
import com.onix.profile.security.accessToken
import com.onix.profile.service.ProfileNavigationService
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.*
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.net.URI
import java.nio.charset.StandardCharsets

fun Application.registerRoutes(
    config: AppConfig,
    account: AccountClient,
    content: ContentClient,
    collections: com.onix.profile.service.CollectionService,
    navigation: ProfileNavigationService
) {

    install(CallLogging)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        })
    }
    install(CORS) {
        config.allowedOrigins.forEach { origin ->
            val url = Url(origin)
            allowHost(url.hostWithPort, schemes = listOf(url.protocol.name))
        }
        allowCredentials = true
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Onix-Redirect")
        allowHeader("X-Profile-Redirect")
    }
    install(StatusPages) {
        exception<AccountUnauthorized> { call, _ -> call.respondAuthRequired(config) }
        exception<AccountForbidden> { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Access denied"))
        }
        exception<AccountNotFound> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Profile not found"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", cause.message ?: "Invalid request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Service unavailable"))
        }
    }
    install(SwaggerUI) {
        info {
            title = "Profile Service API"
            version = "0.1.0"
            description = "Public profile canvas adapter over Account Service"
        }
    }

    routing {
        get("/livez") {
            call.respond(mapOf("status" to "UP"))
        }
        get("/health") {
            call.respond(mapOf("status" to "UP"))
        }
        get("/readyz") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP", "accountGrpc" to config.accountGrpcUrl))
        }
        get("/metrics") {
            call.respondText(
                "# HELP profile_service_up Profile service process is up.\n# TYPE profile_service_up gauge\nprofile_service_up 1\n",
                ContentType.Text.Plain
            )
        }
        route("/openapi.json") { openApiSpec("api") }
        route("/swagger-ui") { swaggerUI("/openapi.json") }

        route("/api") {
            get("/session/me") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                call.respond(SessionMeResponse(account.getMe(token)))
            }

            get("/session/actor") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                call.respond(CurrentActorResponse(account.getCurrentActor(token)))
            }

            get("/profiles/{nickname}") {
                val token = call.accessToken()
                val nickname = call.parameters["nickname"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("nickname is required")
                val actor = token?.let { account.getCurrentActor(it) }
                val currentUser = actor?.user ?: anonymousSession()
                val activeOwner = actor?.activeOwner ?: anonymousOwner()
                val profile = account.getProfile(nickname, token)
                val shell = CanvasMapper.toCanvas(profile, currentUser, activeOwner = activeOwner)
                if (shell.status != "OK") {
                    call.respond(if (token == null) shell.copy(content = ProfileContentSummary(), navigation = emptyList(), permissions = ProfilePermissions(owner = false, canFollow = false)) else shell)
                    return@get
                }
                val contentSummary = content.profileContent(profile.ownerType, profile.id, activeOwner, token.orEmpty())
                recordObservedUsage(navigation, profile.ownerType, profile.id, contentSummary)
                val profileContent = contentSummary
                    .copy(collections = collections.collections(profile.ownerType, profile.id, activeOwner, 80))
                call.respond(CanvasMapper.toCanvas(profile, currentUser, profileContent, activeOwner)
                    .copy(navigation = navigation.navigation(profile.ownerType, profile.id, profile.username, call.serviceFilter())))
            }

            get("/organizations/{orgName}") {
                val token = call.accessToken()
                val orgName = call.parameters["orgName"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("orgName is required")
                val actor = token?.let { account.getCurrentActor(it) }
                val currentUser = actor?.user ?: anonymousSession()
                val activeOwner = actor?.activeOwner ?: anonymousOwner()
                val profile = account.getOrganizationProfile(orgName, token)
                val shell = CanvasMapper.toCanvas(profile, currentUser, activeOwner = activeOwner)
                if (shell.status != "OK") {
                    call.respond(if (token == null) shell.copy(content = ProfileContentSummary(), navigation = emptyList(), permissions = ProfilePermissions(owner = false, canFollow = false)) else shell)
                    return@get
                }
                val contentSummary = content.profileContent(profile.ownerType, profile.id, activeOwner, token.orEmpty())
                recordObservedUsage(navigation, profile.ownerType, profile.id, contentSummary)
                val profileContent = contentSummary
                    .copy(collections = collections.collections(profile.ownerType, profile.id, activeOwner, 80))
                call.respond(CanvasMapper.toCanvas(profile, currentUser, profileContent, activeOwner)
                    .copy(navigation = navigation.navigation(profile.ownerType, profile.id, profile.username, call.serviceFilter())))
            }

            get("/organizations/{orgName}/social") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val orgName = call.parameters["orgName"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("orgName is required")
                val filter = call.request.queryParameters["filter"]?.lowercase()?.takeIf {
                    it == "friends" || it == "subscribers" || it == "subscriptions"
                } ?: "friends"
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 80) ?: 40
                val actor = account.getCurrentActor(token)
                val profile = account.getOrganizationProfile(orgName, token)
                val shell = CanvasMapper.toCanvas(profile, actor.user, activeOwner = actor.activeOwner)
                if (shell.status == "BLOCKED" || shell.status == "PRIVATE") {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(shell.status, "Organization social graph is not available"))
                    return@get
                }

                val owners = loadOwnerSocial(account, profile.ownerType, profile.id, filter, page, limit, token)
                call.respond(SocialCanvasResponse(
                    owner = profile,
                    filter = filter,
                    items = owners.items,
                    totalCount = owners.totalCount,
                    page = page,
                    limit = limit
                ))
            }

            get("/profiles/{nickname}/social") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val nickname = call.parameters["nickname"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("nickname is required")
                val filter = call.request.queryParameters["filter"]?.lowercase()?.takeIf {
                    it == "friends" || it == "subscribers" || it == "subscriptions"
                } ?: "friends"
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 80) ?: 40
                val actor = account.getCurrentActor(token)
                val profile = account.getProfile(nickname, token)
                val shell = CanvasMapper.toCanvas(profile, actor.user, activeOwner = actor.activeOwner)
                if (shell.status == "BLOCKED" || shell.status == "PRIVATE") {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(shell.status, "Profile social graph is not available"))
                    return@get
                }

                val users = loadOwnerSocial(account, profile.ownerType, profile.id, filter, page, limit, token)
                call.respond(SocialCanvasResponse(
                    owner = profile,
                    filter = filter,
                    items = users.items,
                    totalCount = users.totalCount,
                    page = page,
                    limit = limit
                ))
            }

            post("/profiles/{userId}/follow") {
                val token = call.accessToken() ?: return@post call.respondAuthRequired(config)
                val userId = call.parameters["userId"] ?: throw IllegalArgumentException("userId is required")
                call.respond(FollowResponse(account.follow(userId, token)))
            }

            delete("/profiles/{userId}/follow") {
                val token = call.accessToken() ?: return@delete call.respondAuthRequired(config)
                val userId = call.parameters["userId"] ?: throw IllegalArgumentException("userId is required")
                account.unfollow(userId, token)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/owners/{ownerType}/{ownerId}/follow") {
                val token = call.accessToken() ?: return@post call.respondAuthRequired(config)
                val ownerType = call.parameters["ownerType"] ?: throw IllegalArgumentException("ownerType is required")
                val ownerId = call.parameters["ownerId"] ?: throw IllegalArgumentException("ownerId is required")
                call.respond(FollowResponse(account.followOwner(ownerType, ownerId, token)))
            }

            delete("/owners/{ownerType}/{ownerId}/follow") {
                val token = call.accessToken() ?: return@delete call.respondAuthRequired(config)
                val ownerType = call.parameters["ownerType"] ?: throw IllegalArgumentException("ownerType is required")
                val ownerId = call.parameters["ownerId"] ?: throw IllegalArgumentException("ownerId is required")
                account.unfollowOwner(ownerType, ownerId, token)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/profile-search/users") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val query = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                call.respond(account.searchUsers(query, limit, token))
            }

            get("/profile-search/owners") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val query = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                call.respond(account.searchOwners(query, limit, token))
            }

            get("/search") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val params = call.request.queryParameters
                val input = ProfileSearchInput(
                    query = params["q"].orEmpty(),
                    types = params["types"].csv(),
                    tags = params["tags"].csv().map { it.removePrefix("#") },
                    providers = params["providers"].csv(),
                    author = params["author"]?.takeIf(String::isNotBlank),
                    dateFrom = params["dateFrom"]?.takeIf(String::isNotBlank),
                    dateTo = params["dateTo"]?.takeIf(String::isNotBlank),
                    sort = params["sort"]?.takeIf(String::isNotBlank) ?: "relevance",
                    limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20,
                    cursor = params["cursor"]?.takeIf(String::isNotBlank)
                )
                call.respond(content.search(input, actor.activeOwner, token))
            }

            get("/search/suggest") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val query = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10
                val owners = if (query.length >= 2) {
                    account.searchOwners(query, limit, token).map {
                        SearchSuggestion(
                            type = "OWNER",
                            value = it.username,
                            label = it.displayName ?: "@${it.username}",
                            owner = it
                        )
                    }
                } else {
                    emptyList()
                }
                val (contentSuggestions, contentErrors) = content.suggest(query, limit, actor.activeOwner, token)
                call.respond(
                    SearchSuggestResponse(
                        query = query,
                        suggestions = (owners + contentSuggestions)
                            .distinctBy { "${it.type}:${it.value.lowercase()}" }
                            .take(limit),
                        partialErrors = contentErrors
                    )
                )
            }

            get("/owners/{ownerType}/{ownerId}/collections") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val ownerType = call.parameters["ownerType"]?.ownerTypeParam() ?: "USER"
                val ownerId = call.parameters["ownerId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("ownerId is required")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 80
                call.respond(collections.collections(ownerType, ownerId, actor.activeOwner, limit))
            }

            get("/collections/{collectionId}") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val collectionId = call.parameters["collectionId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("collectionId is required")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respond(collections.collection(collectionId, actor.activeOwner, token, limit))
            }

            post("/collections") {
                val token = call.accessToken() ?: return@post call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                call.respond(collections.createCollection(actor.activeOwner, call.receive<CreateCollectionInput>()))
            }

            put("/collections/{collectionId}") {
                val token = call.accessToken() ?: return@put call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val collectionId = call.parameters["collectionId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("collectionId is required")
                call.respond(collections.updateCollection(actor.activeOwner, collectionId, call.receive<UpdateCollectionInput>()))
            }

            delete("/collections/{collectionId}") {
                val token = call.accessToken() ?: return@delete call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val collectionId = call.parameters["collectionId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("collectionId is required")
                collections.deleteCollection(actor.activeOwner, collectionId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/items/{serviceKey}/{itemType}/{itemId}/collections") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                call.respond(collections.itemCollections(actor.activeOwner, call.collectionItemRef()))
            }

            put("/items/{serviceKey}/{itemType}/{itemId}/collections") {
                val token = call.accessToken() ?: return@put call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val ref = call.collectionItemRef()
                val body = call.receive<SetItemCollectionsInput>()
                if (body.collectionIds.isNotEmpty()) {
                    val resolved = content.resolveItems(listOf(ref), actor.activeOwner, token)
                    require(resolved.items.any { it.ref == ref }) { "Item is not available" }
                }
                call.respond(collections.setItemCollections(actor.activeOwner, ref, body.collectionIds))
            }

            get("/owners/{ownerType}/{ownerId}/sections/{buttonKey}") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val ownerType = call.parameters["ownerType"]?.ownerTypeParam() ?: "USER"
                val ownerId = call.parameters["ownerId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("ownerId is required")
                val buttonKey = call.parameters["buttonKey"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("buttonKey is required")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 80
                val cursor = call.request.queryParameters["cursor"]?.takeIf(String::isNotBlank)
                call.respond(content.listOwnerSection(ownerType, ownerId, actor.activeOwner, buttonKey, limit, cursor, token))
            }

            get("/content/recommendations") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val params = call.request.queryParameters
                call.respond(content.recommendationFeed(buildJsonObject {
                    put("chunkX", params["chunkX"]?.toIntOrNull() ?: 0)
                    put("chunkY", params["chunkY"]?.toIntOrNull() ?: 0)
                    put("sessionSeed", params["sessionSeed"]?.takeIf(String::isNotBlank) ?: "default")
                    put("limit", params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 12)
                }, actor.activeOwner, token))
            }

            get("/content/collections") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val params = call.request.queryParameters
                val ownerId = params["ownerId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("ownerId is required")
                val ownerType = params["ownerType"]?.ownerTypeParam() ?: "USER"
                val limit = params["limit"]?.toIntOrNull() ?: 80
                call.respond(collections.collections(ownerType, ownerId, actor.activeOwner, limit))
            }

            get("/content/collections/{collectionId}") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val collectionId = call.parameters["collectionId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("collectionId is required")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respond(collections.collection(collectionId, actor.activeOwner, token, limit))
            }

            post("/content/collections") {
                val token = call.accessToken() ?: return@post call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                call.respond(collections.createCollection(actor.activeOwner, call.receive<CreateCollectionInput>()))
            }

            get("/content/posts/{postId}/collections") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val postId = call.parameters["postId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("postId is required")
                call.respond(collections.itemCollections(actor.activeOwner, ProfileCollectionItemRef("content", "post", postId)))
            }

            put("/content/posts/{postId}/collections") {
                val token = call.accessToken() ?: return@put call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val postId = call.parameters["postId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("postId is required")
                val body = call.receive<JsonObject>()
                val collectionIds = body["collectionIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val ref = ProfileCollectionItemRef("content", "post", postId)
                if (collectionIds.isNotEmpty()) {
                    val resolved = content.resolveItems(listOf(ref), actor.activeOwner, token)
                    require(resolved.items.any { it.ref == ref }) { "Post is not available" }
                }
                call.respond(collections.setItemCollections(actor.activeOwner, ref, collectionIds))
            }

            post("/content/posts/{postId}/like") {
                val token = call.accessToken() ?: return@post call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val postId = call.parameters["postId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("postId is required")
                call.respond(content.likePost(postId, actor.activeOwner, token))
            }

            delete("/content/posts/{postId}/like") {
                val token = call.accessToken() ?: return@delete call.respondAuthRequired(config)
                val actor = account.getCurrentActor(token)
                val postId = call.parameters["postId"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("postId is required")
                call.respond(content.unlikePost(postId, actor.activeOwner, token))
            }
        }
    }
}

private fun String?.csv(): List<String> =
    this.orEmpty()
        .split(",")
        .map(String::trim)
        .filter(String::isNotBlank)

private fun ApplicationCall.serviceFilter(): Set<String> =
    request.queryParameters.getAll("from")
        .orEmpty()
        .flatMap { it.csv() }
        .map { it.lowercase() }
        .filter(String::isNotBlank)
        .toSet()

private fun String.ownerTypeParam(): String =
    if (equals("ORGANIZATION", ignoreCase = true)) "ORGANIZATION" else "USER"

private fun ApplicationCall.collectionItemRef(): ProfileCollectionItemRef =
    ProfileCollectionItemRef(
        serviceKey = parameters["serviceKey"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("serviceKey is required"),
        itemType = parameters["itemType"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("itemType is required"),
        itemId = parameters["itemId"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("itemId is required")
    )

private fun loadOwnerSocial(
    account: AccountClient,
    ownerType: String,
    ownerId: String,
    filter: String,
    page: Int,
    limit: Int,
    token: String
): UserPageResponse =
    when (filter) {
        "subscribers" -> account.ownerFollowers(ownerType, ownerId, page, limit, token)
        "subscriptions" -> account.ownerFollowing(ownerType, ownerId, page, limit, token)
        else -> ownerFriends(account, ownerType, ownerId, page, limit, token)
    }

private fun ownerFriends(
    account: AccountClient,
    ownerType: String,
    ownerId: String,
    page: Int,
    limit: Int,
    token: String
): UserPageResponse {
    val maxAccountPageSize = 100
    val followers = loadAllSocialUsers { currentPage ->
        account.ownerFollowers(ownerType, ownerId, currentPage, maxAccountPageSize, token)
    }
    val following = loadAllSocialUsers { currentPage ->
        account.ownerFollowing(ownerType, ownerId, currentPage, maxAccountPageSize, token)
    }
    val followingIds = following.map { "${it.ownerType}:${it.id}" }.toSet()
    val friends = followers.filter { "${it.ownerType}:${it.id}" in followingIds }
    val from = ((page.coerceAtLeast(1) - 1) * limit).coerceAtMost(friends.size)
    val to = (from + limit).coerceAtMost(friends.size)
    return UserPageResponse(items = friends.subList(from, to), totalCount = friends.size)
}

private fun loadAllSocialUsers(fetch: (Int) -> UserPageResponse): List<RelatedUser> {
    val result = linkedMapOf<String, RelatedUser>()
    var page = 1
    var total = Int.MAX_VALUE
    while (result.size < total && page <= 10) {
        val next = fetch(page)
        total = next.totalCount
        next.items.forEach { result[it.id] = it }
        if (next.items.isEmpty()) break
        page += 1
    }
    return result.values.toList()
}

private fun recordObservedUsage(
    navigation: ProfileNavigationService,
    ownerType: String,
    ownerId: String,
    content: ProfileContentSummary
) {
    if (content.posts.isNotEmpty()) {
        navigation.recordUsage(ownerType, ownerId, "content", "posts")
    }
    if (content.stories.isNotEmpty()) {
        navigation.recordUsage(ownerType, ownerId, "content", "story_archive")
    }
}

private suspend fun ApplicationCall.respondAuthRequired(config: AppConfig) {
    respond(HttpStatusCode.Unauthorized, AuthRequiredResponse(loginUrl = config.loginUrl + redirectTarget(config)))
}

private fun anonymousSession(): SessionUser =
    SessionUser(id = ANONYMOUS_OWNER_ID, username = "guest")

private fun anonymousOwner(): AccountUser =
    AccountUser(id = ANONYMOUS_OWNER_ID, ownerType = "USER", username = "guest")

private const val ANONYMOUS_OWNER_ID = "00000000-0000-0000-0000-000000000000"

private fun ApplicationCall.redirectTarget(config: AppConfig): String {
    val requested = request.headers["X-Onix-Redirect"]?.takeIf(String::isNotBlank)
        ?: request.headers["X-Profile-Redirect"]?.takeIf(String::isNotBlank)
        ?: config.profilePublicUrl
    val target = requested.takeIf { isTrustedRedirect(it, config) } ?: config.profilePublicUrl
    return URLEncoder.encode(target, StandardCharsets.UTF_8)
}

private fun isTrustedRedirect(target: String, config: AppConfig): Boolean {
    val targetOrigin = originOf(target) ?: return false
    return config.trustedRedirectOrigins.any { originOf(it) == targetOrigin }
}

private fun originOf(value: String): String? =
    runCatching {
        val uri = URI(value)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
