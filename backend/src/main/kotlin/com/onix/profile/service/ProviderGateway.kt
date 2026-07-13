package com.onix.profile.service

import com.onix.profile.domain.*

data class ProviderResolveResult(
    val items: List<ProfileCollectionItemView> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

data class ProviderActionResult(
    val result: kotlinx.serialization.json.JsonElement,
    val partialErrors: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap()
)

interface ProviderGateway {
    fun resolveItems(refs: List<ProfileCollectionItemRef>, viewer: AccountUser, accessToken: String): ProviderResolveResult
    fun profileContent(ownerType: String, ownerId: String, viewer: AccountUser, accessToken: String): ProfileContentSummary
    fun search(input: com.onix.profile.content.ProfileSearchInput, viewer: AccountUser, accessToken: String): SearchResponse
    fun suggest(query: String, limit: Int, viewer: AccountUser, accessToken: String): Pair<List<SearchSuggestion>, List<String>>
    fun listOwnerSection(
        ownerType: String,
        ownerId: String,
        viewer: AccountUser,
        buttonKey: String,
        limit: Int,
        cursor: String?,
        accessToken: String
    ): OwnerSectionResponse
    fun performAction(
        serviceKey: String,
        capabilityKey: String,
        actor: AccountUser,
        ref: ProfileCollectionItemRef?,
        params: Map<String, String>,
        accessToken: String
    ): ProviderActionResult
}

class NoopProviderGateway : ProviderGateway {
    override fun resolveItems(refs: List<ProfileCollectionItemRef>, viewer: AccountUser, accessToken: String): ProviderResolveResult =
        ProviderResolveResult(partialErrors = if (refs.isEmpty()) emptyList() else listOf("No provider is configured"))

    override fun profileContent(ownerType: String, ownerId: String, viewer: AccountUser, accessToken: String): ProfileContentSummary =
        ProfileContentSummary()

    override fun search(input: com.onix.profile.content.ProfileSearchInput, viewer: AccountUser, accessToken: String): SearchResponse =
        SearchResponse(query = input.query, partialErrors = listOf("No provider is configured"))

    override fun suggest(query: String, limit: Int, viewer: AccountUser, accessToken: String): Pair<List<SearchSuggestion>, List<String>> =
        emptyList<SearchSuggestion>() to listOf("No provider is configured")

    override fun listOwnerSection(
        ownerType: String,
        ownerId: String,
        viewer: AccountUser,
        buttonKey: String,
        limit: Int,
        cursor: String?,
        accessToken: String
    ): OwnerSectionResponse =
        OwnerSectionResponse(buttonKey = buttonKey, partialErrors = listOf("No provider is configured"))

    override fun performAction(
        serviceKey: String,
        capabilityKey: String,
        actor: AccountUser,
        ref: ProfileCollectionItemRef?,
        params: Map<String, String>,
        accessToken: String
    ): ProviderActionResult =
        ProviderActionResult(kotlinx.serialization.json.JsonObject(emptyMap()), listOf("No provider is configured"))
}
