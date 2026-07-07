package com.onix.profile.api

import com.onix.profile.domain.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CanvasMapper {
    private val defaultPositions = mapOf(
        "avatar" to CanvasPosition(0.0, 0.0),
        "displayName" to CanvasPosition(0.0, -150.0),
        "username" to CanvasPosition(160.0, -70.0),
        "bio" to CanvasPosition(165.0, 70.0),
        "socialLinks" to CanvasPosition(55.0, 165.0),
        "birthday" to CanvasPosition(-95.0, 165.0),
        "social" to CanvasPosition(-235.0, -16.0),
        "posts" to CanvasPosition(320.0, -170.0),
        "stories" to CanvasPosition(370.0, 0.0),
        "comments" to CanvasPosition(320.0, 170.0),
        "followAction" to CanvasPosition(0.0, 130.0)
    )

    fun toCanvas(
        profile: AccountProfile,
        currentUser: SessionUser,
        content: ProfileContentSummary = ProfileContentSummary()
    ): ProfileCanvasResponse {
        if (profile.relationship.isBlocked) {
            return ProfileCanvasResponse(status = "BLOCKED", relationship = profile.relationship)
        }

        val owner = profile.id == currentUser.id
        if (profile.isPrivate && !owner && !profile.relationship.isFollowing && !profile.relationship.isFriend) {
            return ProfileCanvasResponse(
                status = "PRIVATE",
                profile = profile,
                relationship = profile.relationship,
                permissions = ProfilePermissions(
                    owner = false,
                    canFollow = !profile.relationship.isBlocked
                )
            )
        }

        val nodeIds = buildList {
            add("avatar")
            add("displayName")
            add("username")
            if (!profile.bio.isNullOrBlank()) add("bio")
            if (profile.socialLinks.isNotEmpty()) add("socialLinks")
            if (profile.birthday != null) add("birthday")
            add("social")
            if (!owner) add("followAction")
        }

        val nodes = nodeIds.map { id ->
            CanvasNode(
                id = id,
                type = nodeType(id),
                position = defaultPositions.getValue(id),
                data = dataFor(id, profile, content)
            )
        }
        val edges = nodeIds.filterNot { it == "avatar" }.map {
            CanvasEdge(id = "avatar-$it", source = "avatar", target = it)
        }

        return ProfileCanvasResponse(
            status = "OK",
            profile = profile,
            content = content,
            relationship = profile.relationship,
            nodes = nodes,
            edges = edges,
            permissions = ProfilePermissions(
                owner = owner,
                canFollow = !owner && !profile.relationship.isBlocked
            ),
            viewport = CanvasViewport()
        )
    }

    private fun nodeType(id: String) = when (id) {
        "avatar" -> "avatar"
        "followAction" -> "action"
        "bio" -> "text"
        "socialLinks" -> "links"
        "social" -> "social"
        else -> "label"
    }

    private fun dataFor(id: String, profile: AccountProfile, content: ProfileContentSummary): JsonObject {
        val displayName = listOfNotNull(profile.firstName, profile.lastName)
            .joinToString(" ")
            .ifBlank { profile.username }
        return when (id) {
            "avatar" -> JsonObject(mapOf(
                "avatarUrl" to JsonPrimitive(profile.avatarUrl),
                "initials" to JsonPrimitive(displayName.take(2).uppercase())
            ))
            "displayName" -> JsonObject(mapOf("label" to JsonPrimitive(displayName)))
            "username" -> JsonObject(mapOf("label" to JsonPrimitive("@${profile.username}")))
            "bio" -> JsonObject(mapOf("label" to JsonPrimitive(profile.bio.orEmpty())))
            "socialLinks" -> JsonObject(mapOf("count" to JsonPrimitive(profile.socialLinks.size)))
            "birthday" -> JsonObject(mapOf("label" to JsonPrimitive("${profile.birthday?.day}.${profile.birthday?.month}")))
            "social" -> JsonObject(mapOf(
                "label" to JsonPrimitive("Social"),
                "subscribersLabel" to JsonPrimitive("Subscribers"),
                "subscriptionsLabel" to JsonPrimitive("Subscriptions"),
                "subscribers" to JsonPrimitive(profile.followersCount),
                "subscriptions" to JsonPrimitive(profile.followingCount)
            ))
            "followAction" -> JsonObject(mapOf("label" to JsonPrimitive(followLabel(profile.relationship))))
            else -> JsonObject(emptyMap())
        }
    }

    private fun followLabel(relationship: Relationship): String = when {
        relationship.isFollowing -> "Following"
        relationship.hasPendingRequest -> "Requested"
        else -> "Follow"
    }
}
