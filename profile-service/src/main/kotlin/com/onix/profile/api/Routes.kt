package com.onix.profile.api

import com.onix.profile.account.*
import com.onix.profile.config.AppConfig
import com.onix.profile.content.ContentClient
import com.onix.profile.domain.*
import com.onix.profile.security.accessToken
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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun Application.registerRoutes(config: AppConfig) {
    val account = AccountClient(config)
    val content = ContentClient(config.contentApiUrl)

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
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
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

            get("/profiles/{nickname}") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val nickname = call.parameters["nickname"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("nickname is required")
                val actor = account.getCurrentActor(token)
                val profile = account.getProfile(nickname, token)
                val shell = CanvasMapper.toCanvas(profile, actor.user, activeOwner = actor.activeOwner)
                if (shell.status != "OK") {
                    call.respond(shell)
                    return@get
                }
                val profileContent = content.profileContent(profile.ownerType, profile.id, token)
                call.respond(CanvasMapper.toCanvas(profile, actor.user, profileContent, actor.activeOwner))
            }

            get("/organizations/{orgName}") {
                val token = call.accessToken() ?: return@get call.respondAuthRequired(config)
                val orgName = call.parameters["orgName"]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("orgName is required")
                val actor = account.getCurrentActor(token)
                val profile = account.getOrganizationProfile(orgName, token)
                val shell = CanvasMapper.toCanvas(profile, actor.user, activeOwner = actor.activeOwner)
                if (shell.status != "OK") {
                    call.respond(shell)
                    return@get
                }
                val profileContent = content.profileContent(profile.ownerType, profile.id, token)
                call.respond(CanvasMapper.toCanvas(profile, actor.user, profileContent, actor.activeOwner))
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
        }
    }
}

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

private suspend fun ApplicationCall.respondAuthRequired(config: AppConfig) {
    respond(HttpStatusCode.Unauthorized, AuthRequiredResponse(loginUrl = config.loginUrl + redirectTarget(config)))
}

private fun ApplicationCall.redirectTarget(config: AppConfig): String {
    val target = request.headers["X-Profile-Redirect"]?.takeIf(String::isNotBlank)
        ?: config.profilePublicUrl
    return URLEncoder.encode(target, StandardCharsets.UTF_8)
}
