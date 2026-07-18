package com.onix.profile.config

import com.onix.profile.domain.ProfileCollectionItemRef
import com.onix.profile.domain.ProfileContentCollection
import com.onix.profile.service.ProfileRepository
import com.onix.profile.service.StoredNavButton
import com.onix.profile.service.StoredProvider
import com.onix.profile.service.StoredProviderCapability
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ProviderFileRepository private constructor(
    private val delegate: ProfileRepository,
    private val providers: List<StoredProvider>,
    private val capabilities: List<StoredProviderCapability>
) : ProfileRepository by delegate {
    override fun listProviders(): List<StoredProvider> = providers
    override fun listProviderCapabilities(): List<StoredProviderCapability> = capabilities

    override fun listNavButtons(ownerType: String, ownerId: String): List<StoredNavButton> {
        val buttons = mutableListOf(
            StoredNavButton("collections", "profile", "collections", "collections", "Collections", "pi pi-bookmark", "#111827", "canvas", "collections", null, null, null, "collections", 10, false)
        )
        if (capabilities.any { it.serviceKey == "content" && it.capabilityKey == "owner_contribution" }) {
            buttons += StoredNavButton("posts", "content", "posts", "owner_contribution", "Posts", "pi pi-th-large", "#111827", "canvas", "section", null, null, null, "owner_contribution", 20, false)
        }
        if (capabilities.any { it.serviceKey == "content" && it.capabilityKey == "story_archive" }) {
            buttons += StoredNavButton("story_archive", "content", "story_archive", "story_archive", "Archive", "pi pi-history", "#22c55e", "redirect", "redirect", null, "content", "/stories/archive?ownerType={ownerType}&ownerId={ownerId}", "story_archive", 30, false)
        }
        return buttons
    }

    override fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String, usedAt: Instant) = Unit

    companion object {
        fun load(delegate: ProfileRepository, file: String): ProviderFileRepository {
            val path = Path.of(file)
            require(Files.isRegularFile(path)) { "PROFILE_PROVIDERS_FILE does not exist: $file" }
            val root = Files.newInputStream(path).use { Yaml().load<Map<String, Any?>>(it) }.orEmpty()
            val rows = root["providers"] as? List<*> ?: error("PROFILE_PROVIDERS_FILE must contain providers")
            val providers = rows.map { raw ->
                val row = raw as? Map<*, *> ?: error("Provider must be an object")
                val key = row.required("key")
                val target = row.required("grpc_target")
                val frontend = row["frontend_url"]?.toString()?.takeIf(String::isNotBlank)
                val caps = (row["capabilities"] as? List<*>)?.map { it.toString().trim() }?.filter(String::isNotBlank).orEmpty()
                Triple(
                    StoredProvider(
                        key,
                        row["display_name"]?.toString() ?: key.replaceFirstChar(Char::uppercase),
                        target,
                        frontend,
                        true,
                        timeoutMillis(row["timeout"]?.toString())
                    ),
                    caps,
                    key
                )
            }
            val capabilities = providers.flatMap { (_, caps, key) -> caps.map { capability ->
                StoredProviderCapability(key, capability, operation(capability), itemTypes(capability), "{}", true)
            } }
            return ProviderFileRepository(delegate, providers.map { it.first }, capabilities)
        }

        private fun Map<*, *>.required(name: String): String = this[name]?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: error("Provider $name is required")

        private fun timeoutMillis(raw: String?): Long {
            val value = raw?.trim()?.lowercase().orEmpty()
            val millis = when {
                value.endsWith("ms") -> value.removeSuffix("ms").toLongOrNull()
                value.endsWith("s") -> value.removeSuffix("s").toLongOrNull()?.times(1_000)
                else -> value.toLongOrNull()
            } ?: 750
            return millis.coerceIn(100, 10_000)
        }

        private fun operation(capability: String): String = when (capability) {
            "owner_contribution" -> "owner_section"
            "story_archive" -> "redirect"
            "search", "suggest", "resolve_items" -> capability.removeSuffix("_items")
            else -> "action"
        }

        private fun itemTypes(capability: String): List<String> = when (capability) {
            "owner_contribution" -> listOf("post")
            "story_archive" -> listOf("story")
            "search", "suggest" -> listOf("post", "comment")
            else -> emptyList()
        }
    }
}
