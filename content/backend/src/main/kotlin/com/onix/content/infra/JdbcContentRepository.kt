package com.onix.content.infra

import com.onix.content.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.onix.content.service.ContentRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcContentRepository(private val ds: DataSource) : ContentRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun savePost(post: Post): Post {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.posts (id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(post.id))
                    ps.setObject(2, UUID.fromString(post.authorId))
                    ps.setString(3, post.ownerType.name)
                    ps.setObject(4, UUID.fromString(post.ownerId))
                    ps.setString(5, post.title)
                    ps.setString(6, post.text)
                    ps.setBoolean(7, post.allowComments)
                    ps.setString(8, post.visibility.name)
                    ps.setString(9, post.status.name)
                    ps.setInt(10, post.contentVersion)
                    ps.setTimestamp(11, Timestamp.from(post.createdAt))
                    ps.setTimestamp(12, Timestamp.from(post.updatedAt))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "post_blocks", "post_id", post.id, post.blocks)
                saveTags(conn, post.id, post.tags)
                savePostAssets(conn, post.id, post.assets)
                conn.commit()
                return post
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun updatePost(post: Post): Post {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    UPDATE content.posts
                    SET title = ?, text = ?, allow_comments = ?, visibility = ?, status = ?, content_version = ?, pinned_comment_id = ?, updated_at = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, post.title)
                    ps.setString(2, post.text)
                    ps.setBoolean(3, post.allowComments)
                    ps.setString(4, post.visibility.name)
                    ps.setString(5, post.status.name)
                    ps.setInt(6, post.contentVersion)
                    if (post.pinnedCommentId == null) ps.setNull(7, Types.OTHER) else ps.setObject(7, UUID.fromString(post.pinnedCommentId))
                    ps.setTimestamp(8, Timestamp.from(post.updatedAt))
                    ps.setObject(9, UUID.fromString(post.id))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM content.post_blocks WHERE post_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(post.id))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM content.post_tags WHERE post_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(post.id))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM content.post_assets WHERE post_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(post.id))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "post_blocks", "post_id", post.id, post.blocks)
                saveTags(conn, post.id, post.tags)
                savePostAssets(conn, post.id, post.assets)
                conn.commit()
                return post
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun deletePost(postId: String) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                deleteMediaReferences(conn, "post", postId)
                conn.prepareStatement("DELETE FROM content.posts WHERE id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(postId))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun findPost(id: String): Post? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, pinned_comment_id, created_at, updated_at
            FROM content.posts WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapPost(conn, rs) else null }
        }
    }

    override fun findStoredPost(id: String): Post? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, pinned_comment_id, created_at, updated_at
            FROM content.posts WHERE id = ? AND status <> 'DELETED'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapPost(conn, rs) else null }
        }
    }

    override fun findStoredPostByAssetId(assetId: String): Post? {
        val postId = ds.connection.use { conn ->
            conn.prepareStatement("SELECT post_id FROM content.post_assets WHERE asset_id = ? ORDER BY post_id LIMIT 1").use { ps ->
                ps.setString(1, assetId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1).toString() else null }
            }
        }
        return postId?.let(::findStoredPost)
    }

    override fun listDraftPosts(owner: OwnerRef, limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, pinned_comment_id, created_at, updated_at
            FROM content.posts WHERE owner_type = ? AND owner_id = ? AND status = 'DRAFT'
            ORDER BY updated_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, owner.ownerType.name)
            ps.setObject(2, UUID.fromString(owner.ownerId))
            ps.setInt(3, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun savePostEditorDocument(document: PostEditorDocument): PostEditorDocument = ds.connection.use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """INSERT INTO content.post_revisions
                    (id,post_id,revision_no,state,edit_version,allow_comments,hidden_tags,layout_version,updated_at)
                    VALUES (?,?,?,?,?,?,?::jsonb,2,?)
                    ON CONFLICT(id) DO UPDATE SET state=EXCLUDED.state,edit_version=EXCLUDED.edit_version,
                    allow_comments=EXCLUDED.allow_comments,hidden_tags=EXCLUDED.hidden_tags,updated_at=EXCLUDED.updated_at"""
            ).use { ps ->
                ps.setObject(1, UUID.fromString(document.revisionId)); ps.setObject(2, UUID.fromString(document.postId))
                ps.setLong(3, document.revisionNo); ps.setString(4, document.state.name); ps.setLong(5, document.editVersion)
                ps.setBoolean(6, document.allowComments)
                ps.setString(7, json.encodeToString(ListSerializer(String.serializer()), document.tags))
                ps.setTimestamp(8, Timestamp.from(document.updatedAt)); ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM content.post_revision_assets WHERE revision_id=?").use { ps ->
                ps.setObject(1, UUID.fromString(document.revisionId)); ps.executeUpdate()
            }
            document.assets.forEachIndexed { order, asset ->
                val layout = asset.layout ?: defaultAssetLayout(asset, order)
                conn.prepareStatement(
                    """INSERT INTO content.post_revision_assets
                        (revision_id,item_id,asset_id,sort_order,x,y,size_preset,source_snapshot,processing_run_id,generation,failure_code,retry_count)
                        VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)"""
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(document.revisionId)); ps.setString(2, asset.id)
                    ps.setString(3, requireNotNull(asset.assetId)); ps.setInt(4, order); ps.setInt(5, layout.x); ps.setInt(6, layout.y)
                    ps.setString(7, layout.sizePreset.name); ps.setString(8, json.encodeToString(PostAsset.serializer(), asset))
                    ps.setString(9, asset.processingRunId)
                    if (asset.generation == null) ps.setNull(10, Types.BIGINT) else ps.setLong(10, asset.generation)
                    ps.setString(11, asset.failure?.code ?: asset.failureReason); ps.setInt(12, 0); ps.executeUpdate()
                }
            }
            conn.commit(); document
        } catch (error: Throwable) { conn.rollback(); throw error } finally { conn.autoCommit = true }
    }

    override fun findPostEditorDocument(revisionId: String): PostEditorDocument? = ds.connection.use { conn ->
        loadPostEditorDocument(conn, "r.id = ?", revisionId)
    }

    override fun findWorkingPostEditorDocument(postId: String): PostEditorDocument? = ds.connection.use { conn ->
        loadPostEditorDocument(conn, "r.post_id = ? AND r.state IN ('DRAFT','PENDING_SOURCE','PROCESSING_MEDIA','NEEDS_ACTION')", postId)
    }

    override fun updatePostEditorRevisionState(revisionId: String, state: PostRevisionState): PostEditorDocument? = ds.connection.use { conn ->
        conn.prepareStatement("UPDATE content.post_revisions SET state=?,updated_at=NOW() WHERE id=?").use { ps ->
            ps.setString(1, state.name); ps.setObject(2, UUID.fromString(revisionId)); ps.executeUpdate()
        }
        loadPostEditorDocument(conn, "r.id = ?", revisionId)
    }

    private fun loadPostEditorDocument(conn: Connection, predicate: String, id: String): PostEditorDocument? {
        val header = conn.prepareStatement(
            """SELECT r.id,r.post_id,r.revision_no,r.state,r.edit_version,r.allow_comments,r.hidden_tags,r.updated_at
                FROM content.post_revisions r WHERE $predicate ORDER BY r.revision_no DESC LIMIT 1"""
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id)); ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                PostEditorDocument(
                    revisionId = rs.getObject("id").toString(), postId = rs.getObject("post_id").toString(),
                    revisionNo = rs.getLong("revision_no"), editVersion = rs.getLong("edit_version"),
                    state = PostRevisionState.valueOf(rs.getString("state")), assets = emptyList(),
                    tags = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("hidden_tags")),
                    allowComments = rs.getBoolean("allow_comments"), updatedAt = rs.getTimestamp("updated_at").toInstant()
                )
            }
        }
        val assets = conn.prepareStatement(
            "SELECT source_snapshot,x,y,size_preset FROM content.post_revision_assets WHERE revision_id=? ORDER BY sort_order"
        ).use { ps ->
            ps.setObject(1, UUID.fromString(header.revisionId)); ps.executeQuery().use { rs -> rs.rows {
                val asset = json.decodeFromString(PostAsset.serializer(), rs.getString("source_snapshot"))
                asset.copy(layout = PostAssetLayout(asset.assetId ?: asset.id, rs.getInt("x"), rs.getInt("y"), AssetSizePreset.valueOf(rs.getString("size_preset")), 1))
            } }
        }
        return header.copy(assets = assets)
    }

    override fun savePostPublication(publication: PostPublication): PostPublication {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.post_publications
                (draft_id, revision, state, idempotency_key, requested_at, activated_at, failure_asset_ids, processing_run_ids, revision_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (draft_id) DO UPDATE SET
                    revision = EXCLUDED.revision,
                    state = EXCLUDED.state,
                    idempotency_key = EXCLUDED.idempotency_key,
                    requested_at = EXCLUDED.requested_at,
                    activated_at = EXCLUDED.activated_at,
                    failure_asset_ids = EXCLUDED.failure_asset_ids,
                    processing_run_ids = EXCLUDED.processing_run_ids
                    ,revision_id = EXCLUDED.revision_id
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(publication.draftId))
                ps.setLong(2, publication.revision)
                ps.setString(3, publication.state.name)
                ps.setString(4, publication.idempotencyKey)
                ps.setTimestamp(5, Timestamp.from(publication.requestedAt))
                if (publication.activatedAt == null) ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(6, Timestamp.from(publication.activatedAt))
                ps.setString(7, json.encodeToString(ListSerializer(String.serializer()), publication.failureAssetIds))
                ps.setString(8, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), publication.processingRunIds))
                if (publication.revisionId == null) ps.setNull(9, Types.OTHER) else ps.setObject(9, UUID.fromString(publication.revisionId))
                ps.executeUpdate()
            }
        }
        return publication
    }

    override fun activateMediaPublication(post: Post, publication: PostPublication): Pair<Post, PostPublication> {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """UPDATE content.posts SET title=?, text=?, allow_comments=?, visibility=?, status=?, content_version=?, pinned_comment_id=?, updated_at=?, active_revision_id=? WHERE id=?"""
                ).use { ps ->
                    ps.setString(1, post.title); ps.setString(2, post.text); ps.setBoolean(3, post.allowComments)
                    ps.setString(4, post.visibility.name); ps.setString(5, post.status.name); ps.setInt(6, post.contentVersion)
                    if (post.pinnedCommentId == null) ps.setNull(7, Types.OTHER) else ps.setObject(7, UUID.fromString(post.pinnedCommentId))
                    ps.setTimestamp(8, Timestamp.from(post.updatedAt))
                    if (publication.revisionId == null) ps.setNull(9, Types.OTHER) else ps.setObject(9, UUID.fromString(publication.revisionId))
                    ps.setObject(10, UUID.fromString(post.id))
                    require(ps.executeUpdate() == 1) { "Draft publication revision is no longer active" }
                }
                conn.prepareStatement("DELETE FROM content.post_assets WHERE post_id=?").use { ps -> ps.setObject(1, UUID.fromString(post.id)); ps.executeUpdate() }
                savePostAssets(conn, post.id, post.assets)
                conn.prepareStatement(
                    """UPDATE content.post_publications SET state=?, activated_at=?, failure_asset_ids=?::jsonb, processing_run_ids=?::jsonb WHERE draft_id=? AND revision=?"""
                ).use { ps ->
                    ps.setString(1, publication.state.name)
                    if (publication.activatedAt == null) ps.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(2, Timestamp.from(publication.activatedAt))
                    ps.setString(3, json.encodeToString(ListSerializer(String.serializer()), publication.failureAssetIds))
                    ps.setString(4, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), publication.processingRunIds))
                    ps.setObject(5, UUID.fromString(publication.draftId)); ps.setLong(6, publication.revision)
                    require(ps.executeUpdate() == 1) { "Publication revision changed during activation" }
                }
                conn.commit()
                return post to publication
            } catch (error: Throwable) {
                conn.rollback(); throw error
            } finally { conn.autoCommit = true }
        }
    }

    override fun findPostPublication(draftId: String): PostPublication? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT draft_id, revision, state, idempotency_key, requested_at, activated_at, failure_asset_ids, processing_run_ids, revision_id
            FROM content.post_publications WHERE draft_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(draftId))
            ps.executeQuery().use { rs -> if (rs.next()) mapPostPublication(rs) else null }
        }
    }

    override fun listPendingPostPublications(limit: Int): List<PostPublication> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT draft_id, revision, state, idempotency_key, requested_at, activated_at, failure_asset_ids, processing_run_ids, revision_id
            FROM content.post_publications
            WHERE state IN ('PENDING_SOURCE', 'PROCESSING_MEDIA', 'PENDING_MEDIA', 'NEEDS_MEDIA_ACTION')
            ORDER BY requested_at ASC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPostPublication(rs) } }
        }
    }

    override fun recordMediaLifecycleEvent(eventId: String): Boolean = ds.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO content.media_event_inbox (event_id) VALUES (?) ON CONFLICT DO NOTHING"
        ).use { ps ->
            ps.setString(1, eventId)
            ps.executeUpdate() == 1
        }
    }

    override fun mediaLifecycleCursor(): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT last_sequence FROM content.media_event_cursor WHERE consumer_key = 'publication'").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun updateMediaLifecycleCursor(sequence: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.media_event_cursor (consumer_key, last_sequence) VALUES ('publication', ?)
                ON CONFLICT (consumer_key) DO UPDATE SET last_sequence = GREATEST(content.media_event_cursor.last_sequence, EXCLUDED.last_sequence)
                """.trimIndent()
            ).use { ps -> ps.setLong(1, sequence); ps.executeUpdate() }
        }
    }

    override fun listPostsByAuthor(authorId: String, limit: Int): List<Post> = ds.connection.use { conn ->
        listPostsByOwner(OwnerRef(OwnerType.USER, authorId), limit)
    }

    override fun listPostsByOwner(owner: OwnerRef, limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, pinned_comment_id, created_at, updated_at
            FROM content.posts WHERE owner_type = ? AND owner_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, owner.ownerType.name)
            ps.setObject(2, UUID.fromString(owner.ownerId))
            ps.setInt(3, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun listRecentPosts(limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, content_version, pinned_comment_id, created_at, updated_at
            FROM content.posts WHERE status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun listViewerTagAffinity(actor: OwnerRef, limit: Int): List<String> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT tag
            FROM (
                SELECT pt.tag, COUNT(*) AS weight, MAX(pl.created_at) AS latest
                FROM content.post_likes pl
                JOIN content.post_tags pt ON pt.post_id = pl.post_id
                WHERE pl.actor_type = ? AND pl.actor_id = ?
                GROUP BY pt.tag
                UNION ALL
                SELECT pt.tag, SUM(pv.view_count) AS weight, MAX(pv.viewed_at) AS latest
                FROM content.post_views pv
                JOIN content.post_tags pt ON pt.post_id = pv.post_id
                WHERE pv.actor_type = ? AND pv.actor_id = ?
                GROUP BY pt.tag
            ) affinity
            GROUP BY tag
            ORDER BY SUM(weight) DESC, MAX(latest) DESC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            val actorId = UUID.fromString(actor.ownerId)
            ps.setString(1, actor.ownerType.name)
            ps.setObject(2, actorId)
            ps.setString(3, actor.ownerType.name)
            ps.setObject(4, actorId)
            ps.setInt(5, limit)
            ps.executeQuery().use { rs -> rs.rows { rs.getString("tag") } }
        }
    }

    override fun reserveRecommendationPlacement(
        viewer: OwnerRef,
        postId: String,
        constellationKey: String,
        constellationFactory: (List<RecommendationConstellation>) -> RecommendationConstellation,
        placementFactory: (RecommendationConstellation, List<RecommendationPlacement>) -> RecommendationPlacement
    ): RecommendationPlacement = ds.connection.use { conn ->
        conn.autoCommit = false
        try {
            // A viewer-wide advisory lock serializes creation of both constellation
            // anchors and post slots across Content instances.
            conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { ps ->
                ps.setString(1, "content-recommendations:${viewer.ownerType}:${viewer.ownerId}")
                ps.executeQuery().use { it.next() }
            }

            findRecommendationPlacement(conn, viewer, postId)?.let { existing ->
                conn.commit()
                return@use existing
            }

            val constellation = findRecommendationConstellation(conn, viewer, constellationKey)
                ?: constellationFactory(listRecommendationConstellations(conn, viewer, emptySet())).also { created ->
                    require(created.key == constellationKey) { "Constellation key must match placement key" }
                    conn.prepareStatement(
                        """
                        INSERT INTO content.recommendation_constellations
                            (viewer_type, viewer_id, constellation_key, anchor_x, anchor_y, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (viewer_type, viewer_id, constellation_key) DO NOTHING
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, viewer.ownerType.name)
                        ps.setObject(2, UUID.fromString(viewer.ownerId))
                        ps.setString(3, created.key)
                        ps.setDouble(4, created.anchorX)
                        ps.setDouble(5, created.anchorY)
                        ps.setTimestamp(6, Timestamp.from(Instant.now()))
                        ps.executeUpdate()
                    }
                }

            // The advisory lock makes this lookup deterministic even when a
            // deployment has multiple application instances.
            val storedConstellation = findRecommendationConstellation(conn, viewer, constellationKey)
                ?: constellation
            val placement = placementFactory(storedConstellation, listRecommendationPlacements(conn, viewer))
            require(placement.constellationKey == constellationKey) { "Placement constellation key must match" }
            conn.prepareStatement(
                """
                INSERT INTO content.recommendation_post_slots
                    (viewer_type, viewer_id, post_id, constellation_key, salt, world_x, world_y, orbit_order, size_preset, placement_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, viewer.ownerType.name)
                ps.setObject(2, UUID.fromString(viewer.ownerId))
                ps.setObject(3, UUID.fromString(postId))
                ps.setString(4, placement.constellationKey)
                ps.setInt(5, placement.salt)
                ps.setDouble(6, placement.worldX)
                ps.setDouble(7, placement.worldY)
                ps.setInt(8, placement.orbitOrder)
                ps.setString(9, placement.sizePreset.name)
                ps.setInt(10, placement.placementVersion)
                ps.setTimestamp(11, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
            conn.commit()
            placement
        } catch (error: Throwable) {
            conn.rollback()
            throw error
        } finally {
            conn.autoCommit = true
        }
    }

    override fun listRecommendationConstellations(viewer: OwnerRef, keys: Set<String>): List<RecommendationConstellation> =
        ds.connection.use { conn -> listRecommendationConstellations(conn, viewer, keys) }

    override fun setPostLike(postId: String, actor: OwnerRef, liked: Boolean) {
        ds.connection.use { conn ->
            if (liked) {
                conn.prepareStatement(
                    """
                    INSERT INTO content.post_likes (post_id, user_id, actor_type, actor_id, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (post_id, actor_type, actor_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(postId))
                    ps.setObject(2, UUID.fromString(actor.ownerId))
                    ps.setString(3, actor.ownerType.name)
                    ps.setObject(4, UUID.fromString(actor.ownerId))
                    ps.setTimestamp(5, Timestamp.from(Instant.now()))
                    ps.executeUpdate()
                }
            } else {
                conn.prepareStatement("DELETE FROM content.post_likes WHERE post_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(postId))
                    ps.setString(2, actor.ownerType.name)
                    ps.setObject(3, UUID.fromString(actor.ownerId))
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun countPostLikes(postId: String): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM content.post_likes WHERE post_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    override fun isPostLikedBy(postId: String, actor: OwnerRef): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("SELECT 1 FROM content.post_likes WHERE post_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun recordPostView(postId: String, actor: OwnerRef, durationMs: Long, viewedAt: Instant) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.post_views (post_id, user_id, actor_type, actor_id, duration_ms, viewed_at, view_count)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (post_id, actor_type, actor_id) DO UPDATE SET
                    duration_ms = content.post_views.duration_ms + EXCLUDED.duration_ms,
                    viewed_at = EXCLUDED.viewed_at,
                    view_count = content.post_views.view_count + 1
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(postId))
                ps.setObject(2, UUID.fromString(actor.ownerId))
                ps.setString(3, actor.ownerType.name)
                ps.setObject(4, UUID.fromString(actor.ownerId))
                ps.setLong(5, durationMs.coerceAtLeast(0))
                ps.setTimestamp(6, Timestamp.from(viewedAt))
                ps.executeUpdate()
            }
        }
    }

    override fun countPostViews(postId: String): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT COALESCE(SUM(view_count), 0) FROM content.post_views WHERE post_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun countPostViewsByUser(postId: String, actor: OwnerRef): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT view_count FROM content.post_views WHERE post_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun setPollVote(postId: String, blockId: String, actor: OwnerRef, optionId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.poll_votes (post_id, block_id, actor_type, actor_id, option_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (post_id, block_id, actor_type, actor_id)
                DO UPDATE SET option_id = EXCLUDED.option_id, created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(postId))
                ps.setString(2, blockId)
                ps.setString(3, actor.ownerType.name)
                ps.setObject(4, UUID.fromString(actor.ownerId))
                ps.setString(5, optionId)
                ps.setTimestamp(6, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    override fun pollVoteCounts(postId: String, blockId: String): Map<String, Long> = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT option_id, COUNT(*) AS count FROM content.poll_votes WHERE post_id = ? AND block_id = ? GROUP BY option_id"
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setString(2, blockId)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("option_id"), rs.getLong("count"))
                }
            }
        }
    }

    override fun pollVoteForActor(postId: String, blockId: String, actor: OwnerRef): String? = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT option_id FROM content.poll_votes WHERE post_id = ? AND block_id = ? AND actor_type = ? AND actor_id = ?"
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setString(2, blockId)
            ps.setString(3, actor.ownerType.name)
            ps.setObject(4, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("option_id") else null }
        }
    }

    override fun setStoryLike(storyId: String, actor: OwnerRef, liked: Boolean) {
        ds.connection.use { conn ->
            if (liked) {
                conn.prepareStatement(
                    """
                    INSERT INTO content.story_likes (story_id, user_id, actor_type, actor_id, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (story_id, actor_type, actor_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(storyId))
                    ps.setObject(2, UUID.fromString(actor.ownerId))
                    ps.setString(3, actor.ownerType.name)
                    ps.setObject(4, UUID.fromString(actor.ownerId))
                    ps.setTimestamp(5, Timestamp.from(Instant.now()))
                    ps.executeUpdate()
                }
            } else {
                conn.prepareStatement("DELETE FROM content.story_likes WHERE story_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(storyId))
                    ps.setString(2, actor.ownerType.name)
                    ps.setObject(3, UUID.fromString(actor.ownerId))
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun countStoryLikes(storyId: String): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM content.story_likes WHERE story_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(storyId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    override fun isStoryLikedBy(storyId: String, actor: OwnerRef): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("SELECT 1 FROM content.story_likes WHERE story_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(storyId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun setCommentLike(commentId: String, actor: OwnerRef, liked: Boolean) {
        ds.connection.use { conn ->
            if (liked) {
                conn.prepareStatement(
                    """
                    INSERT INTO content.comment_likes (comment_id, user_id, actor_type, actor_id, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (comment_id, actor_type, actor_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(commentId))
                    ps.setObject(2, UUID.fromString(actor.ownerId))
                    ps.setString(3, actor.ownerType.name)
                    ps.setObject(4, UUID.fromString(actor.ownerId))
                    ps.setTimestamp(5, Timestamp.from(Instant.now()))
                    ps.executeUpdate()
                }
            } else {
                conn.prepareStatement("DELETE FROM content.comment_likes WHERE comment_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(commentId))
                    ps.setString(2, actor.ownerType.name)
                    ps.setObject(3, UUID.fromString(actor.ownerId))
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun countCommentLikes(commentId: String): Long = ds.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM content.comment_likes WHERE comment_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(commentId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    override fun isCommentLikedBy(commentId: String, actor: OwnerRef): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("SELECT 1 FROM content.comment_likes WHERE comment_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(commentId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun saveStory(story: Story): Story {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.stories (id, author_id, owner_type, owner_id, visibility, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(story.id))
                    ps.setObject(2, UUID.fromString(story.authorId))
                    ps.setString(3, story.ownerType.name)
                    ps.setObject(4, UUID.fromString(story.ownerId))
                    ps.setString(5, story.visibility.name)
                    ps.setString(6, story.status.name)
                    ps.setTimestamp(7, Timestamp.from(story.createdAt))
                    ps.setTimestamp(8, Timestamp.from(story.expiresAt))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "story_blocks", "story_id", story.id, story.blocks)
                conn.commit()
                return story
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun deleteStory(storyId: String) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                deleteMediaReferences(conn, "story", storyId)
                conn.prepareStatement("DELETE FROM content.stories WHERE id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(storyId))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun findStory(id: String): Story? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, visibility, status, created_at, expires_at
            FROM content.stories WHERE id = ? AND status <> 'DELETED'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapStory(conn, rs) else null }
        }
    }

    override fun findStoryByAssetId(assetId: String): Story? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT s.id, s.author_id, s.owner_type, s.owner_id, s.visibility, s.status, s.created_at, s.expires_at
            FROM content.stories s
            JOIN content.story_blocks b ON b.story_id = s.id
            WHERE b.data_json ->> 'assetId' = ? AND s.status <> 'DELETED'
            ORDER BY s.created_at DESC
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, assetId)
            ps.executeQuery().use { rs -> if (rs.next()) mapStory(conn, rs) else null }
        }
    }

    override fun listActiveStories(now: Instant, limit: Int): List<Story> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, author_id, owner_type, owner_id, visibility, status, created_at, expires_at
                FROM content.stories
                WHERE status = 'ACTIVE' AND expires_at > ?
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setInt(2, limit)
                ps.executeQuery().use { rs -> rs.rows { mapStory(conn, rs) } }
            }
        }

    override fun listActiveStoriesByAuthor(authorId: String, now: Instant, limit: Int): List<Story> =
        listActiveStoriesByOwner(OwnerRef(OwnerType.USER, authorId), now, limit)

    override fun listActiveStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int): List<Story> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, author_id, owner_type, owner_id, visibility, status, created_at, expires_at
                FROM content.stories
                WHERE owner_type = ? AND owner_id = ? AND status = 'ACTIVE' AND expires_at > ?
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, owner.ownerType.name)
                ps.setObject(2, UUID.fromString(owner.ownerId))
                ps.setTimestamp(3, Timestamp.from(now))
                ps.setInt(4, limit)
                ps.executeQuery().use { rs -> rs.rows { mapStory(conn, rs) } }
            }
        }

    override fun listArchivedStoriesByAuthor(authorId: String, now: Instant, limit: Int, cursor: Instant?): List<Story> =
        listArchivedStoriesByOwner(OwnerRef(OwnerType.USER, authorId), now, limit, cursor)

    override fun listArchivedStoriesByOwner(owner: OwnerRef, now: Instant, limit: Int, cursor: Instant?): List<Story> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, author_id, owner_type, owner_id, visibility, status, created_at, expires_at
                FROM content.stories
                WHERE owner_type = ? AND owner_id = ?
                  AND status <> 'DELETED'
                  AND (status = 'ARCHIVED' OR expires_at <= ?)
                  AND (?::timestamptz IS NULL OR created_at < ?)
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, owner.ownerType.name)
                ps.setObject(2, UUID.fromString(owner.ownerId))
                ps.setTimestamp(3, Timestamp.from(now))
                if (cursor == null) {
                    ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE)
                    ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
                } else {
                    val ts = Timestamp.from(cursor)
                    ps.setTimestamp(4, ts)
                    ps.setTimestamp(5, ts)
                }
                ps.setInt(6, limit)
                ps.executeQuery().use { rs -> rs.rows { mapStory(conn, rs) } }
            }
        }

    override fun listArchivedStoryPeriods(owner: OwnerRef, now: Instant, limit: Int): List<StoryArchivePeriod> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT to_char(created_at, 'YYYY-MM') AS period, COUNT(*) AS count, (array_agg(id ORDER BY created_at DESC))[1] AS latest_story_id
            FROM content.stories
            WHERE owner_type = ? AND owner_id = ?
              AND status <> 'DELETED'
              AND (status = 'ARCHIVED' OR expires_at <= ?)
            GROUP BY to_char(created_at, 'YYYY-MM')
            ORDER BY period DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, owner.ownerType.name)
            ps.setObject(2, UUID.fromString(owner.ownerId))
            ps.setTimestamp(3, Timestamp.from(now))
            ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                rs.rows {
                    StoryArchivePeriod(
                        period = rs.getString("period"),
                        count = rs.getInt("count"),
                        latestStoryId = rs.getObject("latest_story_id")?.toString()
                    )
                }
            }
        }
    }

    override fun recordStoryView(storyId: String, actor: OwnerRef, viewedAt: Instant) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.story_views (story_id, user_id, actor_type, actor_id, viewed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (story_id, actor_type, actor_id) DO UPDATE SET viewed_at = EXCLUDED.viewed_at
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(storyId))
                ps.setObject(2, UUID.fromString(actor.ownerId))
                ps.setString(3, actor.ownerType.name)
                ps.setObject(4, UUID.fromString(actor.ownerId))
                ps.setTimestamp(5, Timestamp.from(viewedAt))
                ps.executeUpdate()
            }
        }
    }

    override fun isStoryViewed(storyId: String, actor: OwnerRef): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("SELECT 1 FROM content.story_views WHERE story_id = ? AND actor_type = ? AND actor_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(storyId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun saveComment(comment: Comment): Comment {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.comments (id, post_id, author_id, owner_type, owner_id, parent_id, reply_to_id, text, document_json, content_version, status, pinned_at, created_at, updated_at, edited_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(comment.id))
                    ps.setObject(2, UUID.fromString(comment.postId))
                    ps.setObject(3, UUID.fromString(comment.authorId))
                    ps.setString(4, comment.ownerType.name)
                    ps.setObject(5, UUID.fromString(comment.ownerId))
                    if (comment.parentId == null) ps.setNull(6, Types.OTHER) else ps.setObject(6, UUID.fromString(comment.parentId))
                    if (comment.replyToId == null) ps.setNull(7, Types.OTHER) else ps.setObject(7, UUID.fromString(comment.replyToId))
                    ps.setString(8, comment.text)
                    ps.setString(9, json.encodeToString(CommentDocumentV1.serializer(), comment.document ?: legacyCommentDocument(comment.text)))
                    ps.setInt(10, comment.document?.version ?: 1)
                    ps.setString(11, comment.status.name)
                    if (comment.pinnedAt == null) ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(12, Timestamp.from(comment.pinnedAt))
                    ps.setTimestamp(13, Timestamp.from(comment.createdAt))
                    ps.setTimestamp(14, Timestamp.from(comment.updatedAt))
                    if (comment.editedAt == null) ps.setNull(15, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(15, Timestamp.from(comment.editedAt))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "comment_blocks", "comment_id", comment.id, comment.blocks)
                saveCommentAssets(conn, comment.id, comment.attachments)
                conn.commit()
                return comment
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun updateComment(comment: Comment): Comment {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    UPDATE content.comments
                    SET text = ?, document_json = ?::jsonb, content_version = ?, status = ?, pinned_at = ?, updated_at = ?, edited_at = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, comment.text)
                    ps.setString(2, json.encodeToString(CommentDocumentV1.serializer(), comment.document ?: legacyCommentDocument(comment.text)))
                    ps.setInt(3, comment.document?.version ?: 1)
                    ps.setString(4, comment.status.name)
                    if (comment.pinnedAt == null) ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(5, Timestamp.from(comment.pinnedAt))
                    ps.setTimestamp(6, Timestamp.from(comment.updatedAt))
                    if (comment.editedAt == null) ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setTimestamp(7, Timestamp.from(comment.editedAt))
                    ps.setObject(8, UUID.fromString(comment.id))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM content.comment_blocks WHERE comment_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(comment.id))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM content.comment_assets WHERE comment_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(comment.id))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "comment_blocks", "comment_id", comment.id, comment.blocks)
                saveCommentAssets(conn, comment.id, comment.attachments)
                conn.commit()
                return comment
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun deleteComment(commentId: String) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                deleteMediaReferences(conn, "comment", commentId)
                conn.prepareStatement("DELETE FROM content.comments WHERE id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(commentId))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun findComment(id: String): Comment? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, post_id, author_id, owner_type, owner_id, parent_id, reply_to_id, text, document_json, content_version, status, pinned_at, created_at, updated_at, edited_at
            FROM content.comments WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapComment(conn, rs) else null }
        }
    }

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, post_id, author_id, owner_type, owner_id, parent_id, reply_to_id, text, document_json, content_version, status, pinned_at, created_at, updated_at, edited_at
            FROM content.comments WHERE post_id = ? AND status <> 'HIDDEN'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.rows { mapComment(conn, rs) } }
        }
    }

    override fun savePostSearchProjection(projection: PostSearchProjection): PostSearchProjection {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.post_search_projections (post_id, discussion_text, comment_count, revision, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (post_id) DO UPDATE SET
                    discussion_text = EXCLUDED.discussion_text,
                    comment_count = EXCLUDED.comment_count,
                    revision = EXCLUDED.revision,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(projection.postId))
                ps.setString(2, projection.discussion)
                ps.setInt(3, projection.commentCount)
                ps.setLong(4, projection.revision)
                ps.setTimestamp(5, Timestamp.from(projection.updatedAt))
                ps.executeUpdate()
            }
        }
        return projection
    }

    override fun findPostSearchProjection(postId: String): PostSearchProjection? = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT post_id, discussion_text, comment_count, revision, updated_at FROM content.post_search_projections WHERE post_id = ?"
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else PostSearchProjection(
                    postId = rs.getString("post_id"),
                    discussion = rs.getString("discussion_text"),
                    commentCount = rs.getInt("comment_count"),
                    revision = rs.getLong("revision"),
                    updatedAt = rs.getTimestamp("updated_at").toInstant()
                )
            }
        }
    }

    override fun setPinnedComment(postId: String, commentId: String?, pinnedAt: Instant?) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Serialize replacement of a pin through the post row.  This
                // makes the partial unique index a final guard, not a race.
                conn.prepareStatement("SELECT id FROM content.posts WHERE id = ? FOR UPDATE").use { ps ->
                    ps.setObject(1, UUID.fromString(postId))
                    ps.executeQuery().use { it.next() }
                }
                conn.prepareStatement(
                    """
                    UPDATE content.comments SET pinned_at = NULL
                    WHERE post_id = ? AND parent_id IS NULL AND pinned_at IS NOT NULL
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(postId))
                    ps.executeUpdate()
                }
                if (commentId != null && pinnedAt != null) {
                    conn.prepareStatement(
                        """
                        UPDATE content.comments SET pinned_at = ?
                        WHERE id = ? AND post_id = ? AND parent_id IS NULL AND status = 'ACTIVE'
                        """.trimIndent()
                    ).use { ps ->
                        ps.setTimestamp(1, Timestamp.from(pinnedAt))
                        ps.setObject(2, UUID.fromString(commentId))
                        ps.setObject(3, UUID.fromString(postId))
                        require(ps.executeUpdate() == 1) { "Only active root comments can be pinned" }
                    }
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun saveCommentReport(report: CommentReport) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.comment_reports (comment_id, actor_type, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (comment_id, actor_type, actor_id) DO UPDATE SET reason = EXCLUDED.reason, created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(report.commentId))
                ps.setString(2, report.actor.ownerType.name)
                ps.setObject(3, UUID.fromString(report.actor.ownerId))
                ps.setString(4, report.reason)
                ps.setTimestamp(5, Timestamp.from(report.createdAt))
                ps.executeUpdate()
            }
        }
    }

    override fun hideCommentForViewer(commentId: String, actor: OwnerRef) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.comment_viewer_hides (comment_id, actor_type, actor_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (comment_id, actor_type, actor_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(commentId))
                ps.setString(2, actor.ownerType.name)
                ps.setObject(3, UUID.fromString(actor.ownerId))
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    override fun hiddenCommentIdsForViewer(postId: String, actor: OwnerRef): Set<String> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT h.comment_id
            FROM content.comment_viewer_hides h
            JOIN content.comments c ON c.id = h.comment_id
            WHERE c.post_id = ? AND h.actor_type = ? AND h.actor_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setString(2, actor.ownerType.name)
            ps.setObject(3, UUID.fromString(actor.ownerId))
            ps.executeQuery().use { rs -> rs.rows { rs.getObject("comment_id").toString() }.toSet() }
        }
    }

    override fun saveCollection(collection: SavedCollection): SavedCollection {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.collections (id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(collection.id))
                ps.setString(2, collection.ownerType.name)
                ps.setObject(3, UUID.fromString(collection.ownerId))
                ps.setString(4, collection.title)
                ps.setString(5, collection.description)
                if (collection.cover == null) ps.setNull(6, Types.VARCHAR) else ps.setString(6, json.encodeToString(collection.cover))
                ps.setString(7, collection.visibility.name)
                ps.setTimestamp(8, Timestamp.from(collection.createdAt))
                ps.setTimestamp(9, Timestamp.from(collection.updatedAt))
                ps.executeUpdate()
            }
        }
        return collection
    }

    override fun updateCollection(collection: SavedCollection): SavedCollection {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE content.collections
                SET title = ?, description = ?, cover_json = ?::jsonb, visibility = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, collection.title)
                ps.setString(2, collection.description)
                if (collection.cover == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, json.encodeToString(collection.cover))
                ps.setString(4, collection.visibility.name)
                ps.setTimestamp(5, Timestamp.from(collection.updatedAt))
                ps.setObject(6, UUID.fromString(collection.id))
                ps.executeUpdate()
            }
        }
        return collection
    }

    override fun deleteCollection(collectionId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM content.collections WHERE id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(collectionId))
                ps.executeUpdate()
            }
        }
    }

    override fun findCollection(id: String): SavedCollection? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at
            FROM content.collections WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapCollection(conn, rs) else null }
        }
    }

    override fun listCollectionsByOwner(owner: OwnerRef, limit: Int): List<SavedCollection> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, owner_type, owner_id, title, description, cover_json, visibility, created_at, updated_at
            FROM content.collections
            WHERE owner_type = ? AND owner_id = ?
            ORDER BY updated_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, owner.ownerType.name)
            ps.setObject(2, UUID.fromString(owner.ownerId))
            ps.setInt(3, limit)
            ps.executeQuery().use { rs -> rs.rows { mapCollection(conn, rs) } }
        }
    }

    override fun listCollectionPosts(collectionId: String, limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT p.id, p.author_id, p.owner_type, p.owner_id, p.title, p.text, p.allow_comments, p.visibility, p.status, p.created_at, p.updated_at
            FROM content.collection_items ci
            JOIN content.posts p ON p.id = ci.post_id
            WHERE ci.collection_id = ? AND p.status = 'ACTIVE'
            ORDER BY ci.added_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(collectionId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun listPostCollectionIds(owner: OwnerRef, postId: String): List<String> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT c.id
            FROM content.collection_items ci
            JOIN content.collections c ON c.id = ci.collection_id
            WHERE c.owner_type = ? AND c.owner_id = ? AND ci.post_id = ?
            ORDER BY c.updated_at DESC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, owner.ownerType.name)
            ps.setObject(2, UUID.fromString(owner.ownerId))
            ps.setObject(3, UUID.fromString(postId))
            ps.executeQuery().use { rs -> rs.rows { rs.getObject("id").toString() } }
        }
    }

    override fun addPostToCollection(collectionId: String, postId: String, addedAt: Instant) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.collection_items (collection_id, post_id, added_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (collection_id, post_id) DO UPDATE SET added_at = EXCLUDED.added_at
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(collectionId))
                    ps.setObject(2, UUID.fromString(postId))
                    ps.setTimestamp(3, Timestamp.from(addedAt))
                    ps.executeUpdate()
                }
                conn.prepareStatement("UPDATE content.collections SET updated_at = ? WHERE id = ?").use { ps ->
                    ps.setTimestamp(1, Timestamp.from(addedAt))
                    ps.setObject(2, UUID.fromString(collectionId))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun removePostFromCollection(collectionId: String, postId: String) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM content.collection_items WHERE collection_id = ? AND post_id = ?").use { ps ->
                    ps.setObject(1, UUID.fromString(collectionId))
                    ps.setObject(2, UUID.fromString(postId))
                    ps.executeUpdate()
                }
                conn.prepareStatement("UPDATE content.collections SET updated_at = ? WHERE id = ?").use { ps ->
                    ps.setTimestamp(1, Timestamp.from(Instant.now()))
                    ps.setObject(2, UUID.fromString(collectionId))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun saveMediaReference(reference: ContentMediaReference) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO content.media_references (id, owner_type, owner_id, blob_id, profile, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, reference.ownerType)
                ps.setObject(3, UUID.fromString(reference.ownerId))
                ps.setObject(4, UUID.fromString(reference.blobId))
                ps.setString(5, reference.profile)
                ps.setTimestamp(6, Timestamp.from(reference.createdAt))
                ps.executeUpdate()
            }
        }
    }

    override fun listMediaReferences(blobId: String): List<ContentMediaReference> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT owner_type, owner_id, blob_id, profile, created_at
            FROM content.media_references
            WHERE blob_id = ?
            ORDER BY created_at DESC
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(blobId))
            ps.executeQuery().use { rs ->
                rs.rows {
                    ContentMediaReference(
                        ownerType = rs.getString("owner_type"),
                        ownerId = rs.getObject("owner_id").toString(),
                        blobId = rs.getObject("blob_id").toString(),
                        profile = rs.getString("profile"),
                        createdAt = rs.getTimestamp("created_at").toInstant()
                    )
                }
            }
        }
    }

    override fun findLegacyMediaReferences(blobId: String): List<ContentMediaReference> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT 'post' AS owner_type, post_id AS owner_id
            FROM content.post_blocks
            WHERE data_json ->> 'blobId' = ?
            UNION
            SELECT 'comment' AS owner_type, comment_id AS owner_id
            FROM content.comment_blocks
            WHERE data_json ->> 'blobId' = ?
            UNION
            SELECT 'story' AS owner_type, story_id AS owner_id
            FROM content.story_blocks
            WHERE data_json ->> 'blobId' = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, blobId)
            ps.setString(2, blobId)
            ps.setString(3, blobId)
            ps.executeQuery().use { rs ->
                rs.rows {
                    ContentMediaReference(
                        ownerType = rs.getString("owner_type"),
                        ownerId = rs.getObject("owner_id").toString(),
                        blobId = blobId,
                        createdAt = Instant.EPOCH
                    )
                }
            }
        }
    }

    private fun saveBlocks(conn: Connection, table: String, fk: String, ownerId: String, blocks: List<ContentBlock>) {
        blocks.forEachIndexed { index, block ->
            conn.prepareStatement(
                """
                INSERT INTO content.$table (id, $fk, sort_order, block_type, data_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(block.id))
                ps.setObject(2, UUID.fromString(ownerId))
                ps.setInt(3, index)
                ps.setString(4, block.type.name)
                ps.setString(5, json.encodeToString(block.data))
                ps.executeUpdate()
            }
        }
    }

    private fun saveTags(conn: Connection, postId: String, tags: List<String>) {
        tags.forEach { tag ->
            conn.prepareStatement("INSERT INTO content.post_tags (post_id, tag) VALUES (?, ?) ON CONFLICT DO NOTHING").use { ps ->
                ps.setObject(1, UUID.fromString(postId))
                ps.setString(2, tag)
                ps.executeUpdate()
            }
        }
    }

    private fun savePostAssets(conn: Connection, postId: String, assets: List<PostAsset>) {
        assets.forEachIndexed { order, asset ->
            conn.prepareStatement(
                """
                INSERT INTO content.post_assets
                (id, post_id, sort_order, asset_kind, source_kind, asset_id, source_url, provider, status, variants_json, poster_url, waveform_url, width, height, duration_ms, generation, processing_run_id, delivery_contract, layout_x, layout_y, size_preset, layout_version, source_status, processing_status, delivery_status, failure_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, asset.id)
                ps.setObject(2, UUID.fromString(postId))
                ps.setInt(3, order)
                ps.setString(4, asset.kind.name)
                ps.setString(5, asset.sourceKind.name)
                ps.setString(6, asset.assetId)
                ps.setString(7, asset.url)
                ps.setString(8, asset.provider)
                ps.setString(9, asset.status.name)
                ps.setString(10, json.encodeToString(ListSerializer(AssetVariant.serializer()), asset.variants))
                ps.setString(11, asset.posterUrl)
                ps.setString(12, asset.waveformUrl)
                if (asset.width == null) ps.setNull(13, Types.INTEGER) else ps.setInt(13, asset.width)
                if (asset.height == null) ps.setNull(14, Types.INTEGER) else ps.setInt(14, asset.height)
                if (asset.durationMs == null) ps.setNull(15, Types.BIGINT) else ps.setLong(15, asset.durationMs)
                if (asset.generation == null) ps.setNull(16, Types.BIGINT) else ps.setLong(16, asset.generation)
                ps.setString(17, asset.processingRunId)
                ps.setString(18, asset.deliveryContract)
                val layout = asset.layout ?: defaultAssetLayout(asset, order)
                ps.setInt(19, layout.x)
                ps.setInt(20, layout.y)
                ps.setString(21, layout.sizePreset.name)
                ps.setInt(22, layout.layoutVersion)
                ps.setString(23, asset.sourceStatus?.name)
                ps.setString(24, asset.processingStatus.name)
                ps.setString(25, asset.deliveryStatus.name)
                ps.setString(26, asset.failure?.let { json.encodeToString(MediaFailure.serializer(), it) })
                ps.executeUpdate()
            }
        }
    }

    private fun saveCommentAssets(conn: Connection, commentId: String, assets: List<PostAsset>) {
        assets.forEachIndexed { order, asset ->
            conn.prepareStatement(
                """
                INSERT INTO content.comment_assets
                (id, comment_id, sort_order, asset_kind, asset_id, status, variants_json, poster_url, width, height, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, asset.id)
                ps.setObject(2, UUID.fromString(commentId))
                ps.setInt(3, order)
                ps.setString(4, asset.kind.name)
                ps.setString(5, asset.assetId)
                ps.setString(6, asset.status.name)
                ps.setString(7, json.encodeToString(ListSerializer(AssetVariant.serializer()), asset.variants))
                ps.setString(8, asset.posterUrl)
                if (asset.width == null) ps.setNull(9, Types.INTEGER) else ps.setInt(9, asset.width)
                if (asset.height == null) ps.setNull(10, Types.INTEGER) else ps.setInt(10, asset.height)
                if (asset.durationMs == null) ps.setNull(11, Types.BIGINT) else ps.setLong(11, asset.durationMs)
                ps.executeUpdate()
            }
        }
    }

    private fun deleteMediaReferences(conn: Connection, ownerType: String, ownerId: String) {
        conn.prepareStatement("DELETE FROM content.media_references WHERE owner_type = ? AND owner_id = ?").use { ps ->
            ps.setString(1, ownerType)
            ps.setObject(2, UUID.fromString(ownerId))
            ps.executeUpdate()
        }
    }

    private fun findRecommendationPlacement(conn: Connection, viewer: OwnerRef, postId: String): RecommendationPlacement? =
        conn.prepareStatement(
            """
            SELECT constellation_key, salt, world_x, world_y, orbit_order, size_preset, placement_version
            FROM content.recommendation_post_slots
            WHERE viewer_type = ? AND viewer_id = ? AND post_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, viewer.ownerType.name)
            ps.setObject(2, UUID.fromString(viewer.ownerId))
            ps.setObject(3, UUID.fromString(postId))
            ps.executeQuery().use { rs -> if (rs.next()) mapRecommendationPlacement(rs) else null }
        }

    private fun listRecommendationPlacements(conn: Connection, viewer: OwnerRef): List<RecommendationPlacement> =
        conn.prepareStatement(
            """
            SELECT constellation_key, salt, world_x, world_y, orbit_order, size_preset, placement_version
            FROM content.recommendation_post_slots
            WHERE viewer_type = ? AND viewer_id = ?
            ORDER BY created_at ASC, post_id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, viewer.ownerType.name)
            ps.setObject(2, UUID.fromString(viewer.ownerId))
            ps.executeQuery().use { rs -> rs.rows { mapRecommendationPlacement(rs) } }
        }

    private fun findRecommendationConstellation(conn: Connection, viewer: OwnerRef, key: String): RecommendationConstellation? =
        conn.prepareStatement(
            """
            SELECT constellation_key, anchor_x, anchor_y
            FROM content.recommendation_constellations
            WHERE viewer_type = ? AND viewer_id = ? AND constellation_key = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, viewer.ownerType.name)
            ps.setObject(2, UUID.fromString(viewer.ownerId))
            ps.setString(3, key)
            ps.executeQuery().use { rs -> if (rs.next()) mapRecommendationConstellation(rs) else null }
        }

    private fun listRecommendationConstellations(
        conn: Connection,
        viewer: OwnerRef,
        keys: Set<String>
    ): List<RecommendationConstellation> {
        val requestedKeys = keys.toList().sorted()
        val filter = if (requestedKeys.isEmpty()) "" else " AND constellation_key IN (${requestedKeys.joinToString(",") { "?" }})"
        return conn.prepareStatement(
            """
            SELECT constellation_key, anchor_x, anchor_y
            FROM content.recommendation_constellations
            WHERE viewer_type = ? AND viewer_id = ?$filter
            ORDER BY constellation_key ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, viewer.ownerType.name)
            ps.setObject(2, UUID.fromString(viewer.ownerId))
            requestedKeys.forEachIndexed { index, key -> ps.setString(index + 3, key) }
            ps.executeQuery().use { rs -> rs.rows { mapRecommendationConstellation(rs) } }
        }
    }

    private fun mapRecommendationPlacement(rs: ResultSet): RecommendationPlacement =
        RecommendationPlacement(
            constellationKey = rs.getString("constellation_key"),
            salt = rs.getInt("salt"),
            worldX = rs.getDouble("world_x"),
            worldY = rs.getDouble("world_y"),
            orbitOrder = rs.getInt("orbit_order"),
            sizePreset = AssetSizePreset.valueOf(rs.getString("size_preset")),
            placementVersion = rs.getInt("placement_version")
        )

    private fun mapRecommendationConstellation(rs: ResultSet): RecommendationConstellation =
        RecommendationConstellation(
            key = rs.getString("constellation_key"),
            anchorX = rs.getDouble("anchor_x"),
            anchorY = rs.getDouble("anchor_y")
        )

    private fun mapPost(conn: Connection, rs: ResultSet): Post {
        val id = rs.getObject("id").toString()
        return Post(
            id = id,
            authorId = rs.getObject("author_id").toString(),
            ownerType = ownerType(rs),
            ownerId = ownerId(rs, "author_id"),
            title = rs.getString("title"),
            text = rs.getString("text"),
            blocks = loadBlocks(conn, "post_blocks", "post_id", id),
            assets = loadPostAssets(conn, id),
            tags = loadTags(conn, id),
            allowComments = rs.getBoolean("allow_comments"),
            visibility = Visibility.valueOf(rs.getString("visibility")),
            status = ContentStatus.valueOf(rs.getString("status")),
            contentVersion = runCatching { rs.getInt("content_version") }.getOrDefault(1).coerceAtLeast(1),
            pinnedCommentId = runCatching { rs.getObject("pinned_comment_id")?.toString() }.getOrNull(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun mapPostPublication(rs: ResultSet): PostPublication = PostPublication(
        draftId = rs.getObject("draft_id").toString(),
        revision = rs.getLong("revision"),
        state = PostPublicationState.valueOf(rs.getString("state")),
        idempotencyKey = rs.getString("idempotency_key"),
        requestedAt = rs.getTimestamp("requested_at").toInstant(),
        activatedAt = rs.getTimestamp("activated_at")?.toInstant(),
        failureAssetIds = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("failure_asset_ids")),
        processingRunIds = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), rs.getString("processing_run_ids"))
        ,revisionId = rs.getObject("revision_id")?.toString()
    )

    private fun mapStory(conn: Connection, rs: ResultSet): Story {
        val id = rs.getObject("id").toString()
        return Story(
            id = id,
            authorId = rs.getObject("author_id").toString(),
            ownerType = ownerType(rs),
            ownerId = ownerId(rs, "author_id"),
            blocks = loadBlocks(conn, "story_blocks", "story_id", id),
            visibility = Visibility.valueOf(rs.getString("visibility")),
            status = ContentStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant()
        )
    }

    private fun mapComment(conn: Connection, rs: ResultSet): Comment {
        val id = rs.getObject("id").toString()
        return Comment(
            id = id,
            postId = rs.getObject("post_id").toString(),
            authorId = rs.getObject("author_id").toString(),
            ownerType = ownerType(rs),
            ownerId = ownerId(rs, "author_id"),
            parentId = rs.getObject("parent_id")?.toString(),
            replyToId = runCatching { rs.getObject("reply_to_id")?.toString() }.getOrNull(),
            text = rs.getString("text"),
            document = runCatching {
                json.decodeFromString(CommentDocumentV1.serializer(), rs.getString("document_json"))
            }.getOrNull(),
            blocks = loadBlocks(conn, "comment_blocks", "comment_id", id),
            attachments = loadCommentAssets(conn, id),
            status = ContentStatus.valueOf(rs.getString("status")),
            pinnedAt = runCatching { rs.getTimestamp("pinned_at")?.toInstant() }.getOrNull(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            editedAt = runCatching { rs.getTimestamp("edited_at")?.toInstant() }.getOrNull()
        )
    }

    private fun mapCollection(conn: Connection, rs: ResultSet): SavedCollection {
        val id = rs.getObject("id").toString()
        val coverJson = rs.getString("cover_json")?.let { json.decodeFromString(JsonObject.serializer(), it) }
        return SavedCollection(
            id = id,
            ownerType = OwnerType.valueOf(rs.getString("owner_type")),
            ownerId = rs.getObject("owner_id").toString(),
            title = rs.getString("title"),
            description = rs.getString("description"),
            cover = coverJson,
            visibility = CollectionVisibility.valueOf(rs.getString("visibility")),
            itemCount = countCollectionItems(conn, id),
            previewBlocks = loadCollectionPreviewBlocks(conn, id),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun countCollectionItems(conn: Connection, collectionId: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM content.collection_items WHERE collection_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(collectionId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun loadCollectionPreviewBlocks(conn: Connection, collectionId: String): List<ContentBlock> =
        conn.prepareStatement(
            """
            SELECT pb.id, pb.block_type, pb.data_json
            FROM content.collection_items ci
            JOIN content.post_blocks pb ON pb.post_id = ci.post_id
            WHERE ci.collection_id = ? AND pb.block_type IN ('IMAGE', 'VIDEO')
            ORDER BY ci.added_at DESC, pb.sort_order ASC
            LIMIT 3
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(collectionId))
            ps.executeQuery().use { rs ->
                rs.rows {
                    ContentBlock(
                        id = rs.getObject("id").toString(),
                        type = ContentBlockType.valueOf(rs.getString("block_type")),
                        data = json.decodeFromString(JsonObject.serializer(), rs.getString("data_json"))
                    )
                }
            }
        }

    private fun loadBlocks(conn: Connection, table: String, fk: String, ownerId: String): List<ContentBlock> =
        conn.prepareStatement(
            """
            SELECT id, block_type, data_json FROM content.$table
            WHERE $fk = ? ORDER BY sort_order ASC
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(ownerId))
            ps.executeQuery().use { rs ->
                rs.rows {
                    ContentBlock(
                        id = rs.getObject("id").toString(),
                        type = ContentBlockType.valueOf(rs.getString("block_type")),
                        data = json.decodeFromString(JsonObject.serializer(), rs.getString("data_json"))
                    )
                }
            }
        }

    private fun loadTags(conn: Connection, postId: String): List<String> =
        conn.prepareStatement("SELECT tag FROM content.post_tags WHERE post_id = ? ORDER BY tag ASC").use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.executeQuery().use { rs -> rs.rows { rs.getString("tag") } }
        }

    private fun loadPostAssets(conn: Connection, postId: String): List<PostAsset> =
        conn.prepareStatement(
            """
            SELECT id, asset_kind, source_kind, asset_id, source_url, provider, status, variants_json, poster_url, waveform_url, width, height, duration_ms, generation, processing_run_id, delivery_contract, layout_x, layout_y, size_preset, layout_version, source_status, processing_status, delivery_status, failure_json
            FROM content.post_assets WHERE post_id = ? ORDER BY sort_order ASC
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.executeQuery().use { rs -> rs.rows { mapPostAsset(rs) } }
        }

    private fun loadCommentAssets(conn: Connection, commentId: String): List<PostAsset> =
        conn.prepareStatement(
            """
            SELECT id, asset_kind, asset_id, status, variants_json, poster_url, width, height, duration_ms
            FROM content.comment_assets WHERE comment_id = ? ORDER BY sort_order ASC
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(commentId))
            ps.executeQuery().use { rs ->
                rs.rows {
                    PostAsset(
                        id = rs.getString("id"),
                        kind = PostAssetKind.valueOf(rs.getString("asset_kind")),
                        sourceKind = PostAssetSourceKind.UPLOAD,
                        assetId = rs.getString("asset_id"),
                        status = MediaAssetStatus.valueOf(rs.getString("status")),
                        variants = decodeVariants(rs.getString("variants_json")),
                        posterUrl = rs.getString("poster_url"),
                        width = rs.getObject("width") as? Int,
                        height = rs.getObject("height") as? Int,
                        durationMs = (rs.getObject("duration_ms") as? Number)?.toLong()
                    )
                }
            }
        }

    private fun mapPostAsset(rs: ResultSet): PostAsset =
        PostAsset(
            id = rs.getString("id"),
            kind = PostAssetKind.valueOf(rs.getString("asset_kind")),
            sourceKind = PostAssetSourceKind.valueOf(rs.getString("source_kind")),
            assetId = rs.getString("asset_id"),
            url = rs.getString("source_url"),
            provider = rs.getString("provider"),
            status = MediaAssetStatus.valueOf(rs.getString("status")),
            sourceStatus = rs.getString("source_status")?.let(MediaSourceStatus::valueOf),
            processingStatus = rs.getString("processing_status")?.let(MediaProcessingStatus::valueOf) ?: MediaProcessingStatus.NONE,
            deliveryStatus = rs.getString("delivery_status")?.let(MediaDeliveryStatus::valueOf) ?: MediaDeliveryStatus.NONE,
            failure = rs.getString("failure_json")?.let { raw -> runCatching { json.decodeFromString(MediaFailure.serializer(), raw) }.getOrNull() },
            variants = decodeVariants(rs.getString("variants_json")),
            posterUrl = rs.getString("poster_url"),
            waveformUrl = rs.getString("waveform_url"),
            width = rs.getObject("width") as? Int,
            height = rs.getObject("height") as? Int,
            durationMs = (rs.getObject("duration_ms") as? Number)?.toLong()
            ,generation = (rs.getObject("generation") as? Number)?.toLong()
            ,processingRunId = rs.getString("processing_run_id")
            ,deliveryContract = rs.getString("delivery_contract"),
            layout = PostAssetLayout(
                assetId = rs.getString("asset_id") ?: rs.getString("id"),
                x = rs.getInt("layout_x"),
                y = rs.getInt("layout_y"),
                sizePreset = AssetSizePreset.valueOf(rs.getString("size_preset")),
                layoutVersion = rs.getInt("layout_version")
            )
        )

    private fun decodeVariants(raw: String?): List<AssetVariant> = raw
        ?.let { value -> runCatching { json.decodeFromString(ListSerializer(AssetVariant.serializer()), value) }.getOrDefault(emptyList()) }
        .orEmpty()

    private fun legacyCommentDocument(text: String): CommentDocumentV1 = CommentDocumentV1(
        blocks = if (text.isBlank()) emptyList() else listOf(
            CommentDocumentBlock(
                type = CommentBlockType.PARAGRAPH,
                content = listOf(CommentInlineNode(text))
            )
        )
    )

    private fun defaultAssetLayout(asset: PostAsset, order: Int): PostAssetLayout = PostAssetLayout(
        assetId = asset.assetId ?: asset.id,
        x = (order % 4) * 420 - 630,
        y = (order / 4) * 420 - 420,
        sizePreset = AssetSizePreset.M
    )

    private fun ownerType(rs: ResultSet): OwnerType =
        runCatching { OwnerType.valueOf(rs.getString("owner_type")) }.getOrDefault(OwnerType.USER)

    private fun ownerId(rs: ResultSet, fallbackColumn: String): String =
        runCatching { rs.getObject("owner_id")?.toString() }.getOrNull() ?: rs.getObject(fallbackColumn).toString()

    private fun <T> ResultSet.rows(mapper: () -> T): List<T> {
        val items = mutableListOf<T>()
        while (next()) items.add(mapper())
        return items
    }
}
