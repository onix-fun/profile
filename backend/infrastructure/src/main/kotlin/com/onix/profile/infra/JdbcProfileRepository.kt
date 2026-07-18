package com.onix.profile.infra

import com.onix.profile.domain.ProfileCollectionItemRef
import com.onix.profile.domain.ProfileContentCollection
import com.onix.profile.domain.UuidV7
import com.onix.profile.service.ProfileRepository
import com.onix.profile.service.StoredProvider
import com.onix.profile.service.StoredProviderCapability
import com.onix.profile.service.StoredNavButton
import com.onix.profile.service.StoredPublicProfile
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

    override fun findPublicProfile(ownerType: String, ownerId: String): StoredPublicProfile? = ds.connection.use { connection ->
        connection.prepareStatement("""
            SELECT owner_type, owner_id, username, display_name, bio, avatar_asset_id, social_links::text, revision, updated_at
            FROM profile.public_profiles WHERE owner_type = ? AND owner_id = ?::uuid
        """.trimIndent()).use { statement ->
            statement.setString(1, ownerType)
            statement.setString(2, ownerId)
            statement.executeQuery().use { result -> if (result.next()) result.toPublicProfile() else null }
        }
    }

    override fun updatePublicProfile(ownerType: String, ownerId: String, username: String, displayName: String, bio: String, socialLinksJson: String, expectedRevision: Long): StoredPublicProfile =
        mutatePublicProfile(ownerType, ownerId, expectedRevision, "profile.updated", """
            INSERT INTO profile.public_profiles (owner_type, owner_id, username, display_name, bio, social_links, revision)
            VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, 1)
            ON CONFLICT (owner_type, owner_id) DO UPDATE
            SET username = EXCLUDED.username, display_name = EXCLUDED.display_name, bio = EXCLUDED.bio,
                social_links = EXCLUDED.social_links, revision = profile.public_profiles.revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE profile.public_profiles.revision = ?
        """.trimIndent()) { statement ->
            statement.setString(1, ownerType)
            statement.setString(2, ownerId)
            statement.setString(3, username.trim().take(120))
            statement.setString(4, displayName.trim().take(160))
            statement.setString(5, bio.trim().take(2000))
            statement.setString(6, socialLinksJson)
            statement.setLong(7, expectedRevision)
        }

    override fun setAvatar(ownerType: String, ownerId: String, avatarAssetId: String, expectedRevision: Long): StoredPublicProfile =
        mutatePublicProfile(ownerType, ownerId, expectedRevision, "profile.avatar_set", """
            UPDATE profile.public_profiles
            SET avatar_asset_id = ?::uuid, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE owner_type = ? AND owner_id = ?::uuid AND revision = ?
        """.trimIndent()) { statement ->
            statement.setString(1, avatarAssetId)
            statement.setString(2, ownerType)
            statement.setString(3, ownerId)
            statement.setLong(4, expectedRevision)
        }

    private fun mutatePublicProfile(
        ownerType: String,
        ownerId: String,
        expectedRevision: Long,
        eventType: String,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit
    ): StoredPublicProfile = ds.connection.use { connection ->
        connection.autoCommit = false
        try {
            val changed = connection.prepareStatement(sql).use { statement -> bind(statement); statement.executeUpdate() }
            check(changed == 1) { "Public profile was not found or revision $expectedRevision is stale" }
            val profile = connection.prepareStatement("""
                SELECT owner_type, owner_id, username, display_name, bio, avatar_asset_id, social_links::text, revision, updated_at
                FROM profile.public_profiles WHERE owner_type = ? AND owner_id = ?::uuid
            """.trimIndent()).use { statement ->
                statement.setString(1, ownerType); statement.setString(2, ownerId)
                statement.executeQuery().use { result -> check(result.next()); result.toPublicProfile() }
            }
            connection.prepareStatement("""
                INSERT INTO profile.outbox_events (event_id, aggregate_type, aggregate_id, revision, event_type, payload_json)
                VALUES (?::uuid, 'public_profile', ?::uuid, ?, ?, jsonb_build_object(
                    'owner_type', ?, 'owner_id', ?, 'username', ?, 'display_name', ?, 'bio', ?,
                    'avatar_asset_id', ?, 'social_links', ?::jsonb, 'updated_at', ?::timestamptz, 'revision', ?
                ))
            """.trimIndent()).use { statement ->
                statement.setString(1, UuidV7.generate().toString())
                statement.setString(2, ownerId)
                statement.setLong(3, profile.revision)
                statement.setString(4, eventType)
                statement.setString(5, ownerType)
                statement.setString(6, ownerId)
                statement.setString(7, profile.username)
                statement.setString(8, profile.displayName)
                statement.setString(9, profile.bio)
                statement.setString(10, profile.avatarAssetId)
                statement.setString(11, profile.socialLinksJson)
                statement.setTimestamp(12, Timestamp.from(profile.updatedAt))
                statement.setLong(13, profile.revision)
                statement.executeUpdate()
            }
            connection.commit()
            profile
        } catch (error: Throwable) {
            connection.rollback(); throw error
        } finally { connection.autoCommit = true }
    }

    private fun ResultSet.toPublicProfile() = StoredPublicProfile(
        ownerType = getString("owner_type"), ownerId = getObject("owner_id").toString(), username = getString("username"),
        displayName = getString("display_name"), bio = getString("bio"), avatarAssetId = getObject("avatar_asset_id")?.toString(),
        socialLinksJson = getString("social_links"), revision = getLong("revision"), updatedAt = getTimestamp("updated_at").toInstant()
    )

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
                ps.setString(5, ref.itemId)
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
                    ps.setString(4, ref.itemId)
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
                    ps.setString(4, ref.itemId)
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

    // Provider metadata is deliberately unavailable from persistence. The composition root always
    // wraps this repository with ProviderFileRepository, which owns the restart-applied catalog.
    override fun listProviders(): List<StoredProvider> = emptyList()
    override fun listProviderCapabilities(): List<StoredProviderCapability> = emptyList()
    override fun listNavButtons(ownerType: String, ownerId: String): List<StoredNavButton> = emptyList()
    override fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String, usedAt: Instant) = Unit

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
