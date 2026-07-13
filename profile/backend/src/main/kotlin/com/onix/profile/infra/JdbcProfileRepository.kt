package com.onix.profile.infra

import com.onix.profile.domain.ProfileCollectionItemRef
import com.onix.profile.domain.ProfileContentCollection
import com.onix.profile.service.ProfileRepository
import com.onix.profile.service.StoredProvider
import com.onix.profile.service.StoredProviderCapability
import com.onix.profile.service.StoredNavButton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcProfileRepository(private val ds: DataSource) : ProfileRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun saveCollection(collection: ProfileContentCollection): ProfileContentCollection {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO profile.collections (id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(collection.id))
                ps.setString(2, collection.ownerType)
                ps.setObject(3, UUID.fromString(collection.ownerId))
                ps.setString(4, collection.title)
                ps.setString(5, collection.description)
                if (collection.cover == null) ps.setNull(6, Types.VARCHAR) else ps.setString(6, json.encodeToString(collection.cover))
                ps.setString(7, collection.visibility)
                ps.setTimestamp(8, Timestamp.from(Instant.parse(collection.createdAt)))
                ps.setTimestamp(9, Timestamp.from(Instant.parse(collection.updatedAt)))
                ps.executeUpdate()
            }
        }
        return collection
    }

    override fun updateCollection(collection: ProfileContentCollection): ProfileContentCollection {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE profile.collections
                SET title = ?, description = ?, cover_json = ?::jsonb, visibility = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, collection.title)
                ps.setString(2, collection.description)
                if (collection.cover == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, json.encodeToString(collection.cover))
                ps.setString(4, collection.visibility)
                ps.setTimestamp(5, Timestamp.from(Instant.parse(collection.updatedAt)))
                ps.setObject(6, UUID.fromString(collection.id))
                ps.executeUpdate()
            }
        }
        return findCollection(collection.id) ?: collection
    }

    override fun deleteCollection(collectionId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM profile.collections WHERE id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(collectionId))
                ps.executeUpdate()
            }
        }
    }

    override fun findCollection(collectionId: String): ProfileContentCollection? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at
            FROM profile.collections WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(collectionId))
            ps.executeQuery().use { rs -> if (rs.next()) mapCollection(rs, countCollectionItems(collectionId)) else null }
        }
    }

    override fun listCollectionsByOwner(ownerType: String, ownerId: String, limit: Int): List<ProfileContentCollection> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at
                FROM profile.collections
                WHERE owner_type = ? AND owner_id = ?
                ORDER BY updated_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerType)
                ps.setObject(2, UUID.fromString(ownerId))
                ps.setInt(3, limit)
                ps.executeQuery().use { rs -> rs.rows { mapCollection(rs, countCollectionItems(rs.getObject("id").toString())) } }
            }
        }

    override fun listCollectionItems(collectionId: String, limit: Int): List<ProfileCollectionItemRef> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT service_key, item_type, item_id
                FROM profile.collection_items
                WHERE collection_id = ?
                ORDER BY added_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(collectionId))
                ps.setInt(2, limit)
                ps.executeQuery().use { rs -> rs.rows { mapRef(rs) } }
            }
        }

    override fun listItemCollectionIds(ownerType: String, ownerId: String, ref: ProfileCollectionItemRef): List<String> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT c.id
                FROM profile.collection_items ci
                JOIN profile.collections c ON c.id = ci.collection_id
                WHERE c.owner_type = ? AND c.owner_id = ?
                  AND ci.service_key = ? AND ci.item_type = ? AND ci.item_id = ?
                ORDER BY c.updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerType)
                ps.setObject(2, UUID.fromString(ownerId))
                ps.setString(3, ref.serviceKey)
                ps.setString(4, ref.itemType)
                ps.setObject(5, UUID.fromString(ref.itemId))
                ps.executeQuery().use { rs -> rs.rows { rs.getObject("id").toString() } }
            }
        }

    override fun addItemToCollection(collectionId: String, ref: ProfileCollectionItemRef, addedAt: Instant) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO profile.collection_items (collection_id, service_key, item_type, item_id, added_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (collection_id, service_key, item_type, item_id) DO UPDATE SET added_at = EXCLUDED.added_at
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(collectionId))
                    ps.setString(2, ref.serviceKey)
                    ps.setString(3, ref.itemType)
                    ps.setObject(4, UUID.fromString(ref.itemId))
                    ps.setTimestamp(5, Timestamp.from(addedAt))
                    ps.executeUpdate()
                }
                touchCollection(conn, collectionId, addedAt)
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun removeItemFromCollection(collectionId: String, ref: ProfileCollectionItemRef) {
        val now = Instant.now()
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    DELETE FROM profile.collection_items
                    WHERE collection_id = ? AND service_key = ? AND item_type = ? AND item_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(collectionId))
                    ps.setString(2, ref.serviceKey)
                    ps.setString(3, ref.itemType)
                    ps.setObject(4, UUID.fromString(ref.itemId))
                    ps.executeUpdate()
                }
                touchCollection(conn, collectionId, now)
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun listProviders(): List<StoredProvider> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT service_key, display_name, grpc_target_env, frontend_base_url_env, enabled
                FROM profile.service_providers
                WHERE enabled = true
                ORDER BY service_key
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.rows {
                        StoredProvider(
                            serviceKey = rs.getString("service_key"),
                            displayName = rs.getString("display_name"),
                            grpcTargetEnv = rs.getString("grpc_target_env"),
                            frontendBaseUrlEnv = rs.getString("frontend_base_url_env"),
                            enabled = rs.getBoolean("enabled")
                        )
                    }
                }
            }
        }

    override fun listProviderCapabilities(): List<StoredProviderCapability> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT c.service_key, c.capability_key, c.operation, c.item_types, c.config_json::text AS config_json, c.enabled
                FROM profile.provider_capabilities c
                JOIN profile.service_providers p ON p.service_key = c.service_key AND p.enabled = true
                WHERE c.enabled = true
                ORDER BY c.service_key, c.capability_key
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.rows {
                        StoredProviderCapability(
                            serviceKey = rs.getString("service_key"),
                            capabilityKey = rs.getString("capability_key"),
                            operation = rs.getString("operation"),
                            itemTypes = (rs.getArray("item_types")?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                            configJson = rs.getString("config_json") ?: "{}",
                            enabled = rs.getBoolean("enabled")
                        )
                    }
                }
            }
        }

    override fun listNavButtons(ownerType: String, ownerId: String): List<StoredNavButton> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT b.button_key, b.service_key, b.feature_key, b.capability_key, b.label, b.icon, b.color,
                       COALESCE(b.mode, 'canvas') AS mode,
                       b.kind, b.frontend_route_template, b.target_service, b.target_path_template,
                       b.backend_operation, b.sort_order, b.requires_usage
                FROM profile.service_nav_buttons b
                JOIN profile.service_providers p
                  ON p.service_key = b.service_key AND p.enabled = true
                LEFT JOIN profile.provider_capabilities c
                  ON c.service_key = b.service_key AND c.capability_key = b.capability_key
                LEFT JOIN profile.owner_service_usage u
                  ON u.owner_type = ? AND u.owner_id = ? AND u.service_key = b.service_key AND u.feature_key = b.feature_key
                WHERE b.enabled = true
                  AND (b.service_key = 'profile' OR c.enabled = true)
                  AND (b.requires_usage = false OR u.owner_id IS NOT NULL)
                ORDER BY b.sort_order ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerType)
                ps.setObject(2, UUID.fromString(ownerId))
                ps.executeQuery().use { rs ->
                    rs.rows {
                        StoredNavButton(
                            key = rs.getString("button_key"),
                            serviceKey = rs.getString("service_key"),
                            featureKey = rs.getString("feature_key"),
                            capabilityKey = rs.getString("capability_key"),
                            label = rs.getString("label"),
                            icon = rs.getString("icon"),
                            color = rs.getString("color"),
                            mode = rs.getString("mode"),
                            kind = rs.getString("kind"),
                            frontendRouteTemplate = rs.getString("frontend_route_template"),
                            targetService = rs.getString("target_service"),
                            targetPathTemplate = rs.getString("target_path_template"),
                            backendOperation = rs.getString("backend_operation"),
                            sortOrder = rs.getInt("sort_order"),
                            requiresUsage = rs.getBoolean("requires_usage")
                        )
                    }
                }
            }
        }

    override fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String, usedAt: Instant) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO profile.owner_service_usage (owner_type, owner_id, service_key, feature_key, first_used_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_type, owner_id, service_key, feature_key) DO UPDATE SET last_used_at = EXCLUDED.last_used_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerType)
                ps.setObject(2, UUID.fromString(ownerId))
                ps.setString(3, serviceKey)
                ps.setString(4, featureKey)
                ps.setTimestamp(5, Timestamp.from(usedAt))
                ps.setTimestamp(6, Timestamp.from(usedAt))
                ps.executeUpdate()
            }
        }
    }

    private fun touchCollection(conn: java.sql.Connection, collectionId: String, at: Instant) {
        conn.prepareStatement("UPDATE profile.collections SET updated_at = ? WHERE id = ?").use { ps ->
            ps.setTimestamp(1, Timestamp.from(at))
            ps.setObject(2, UUID.fromString(collectionId))
            ps.executeUpdate()
        }
    }

    private fun countCollectionItems(collectionId: String): Int =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM profile.collection_items WHERE collection_id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(collectionId))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    private fun mapCollection(rs: ResultSet, itemCount: Int): ProfileContentCollection {
        val cover = rs.getString("cover_json")?.let { json.decodeFromString(JsonObject.serializer(), it) }
        return ProfileContentCollection(
            id = rs.getObject("id").toString(),
            ownerType = rs.getString("owner_type"),
            ownerId = rs.getObject("owner_id").toString(),
            title = rs.getString("title"),
            description = rs.getString("description"),
            cover = cover,
            visibility = rs.getString("visibility"),
            itemCount = itemCount,
            previewBlocks = emptyList(),
            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
        )
    }

    private fun mapRef(rs: ResultSet): ProfileCollectionItemRef =
        ProfileCollectionItemRef(
            serviceKey = rs.getString("service_key"),
            itemType = rs.getString("item_type"),
            itemId = rs.getObject("item_id").toString()
        )

    private fun <T> ResultSet.rows(mapper: () -> T): List<T> {
        val rows = mutableListOf<T>()
        while (next()) rows.add(mapper())
        return rows
    }
}
