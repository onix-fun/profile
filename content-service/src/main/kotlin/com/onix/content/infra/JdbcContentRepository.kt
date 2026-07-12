package com.onix.content.infra

import com.onix.content.domain.*
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
                    INSERT INTO content.posts (id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    ps.setTimestamp(10, Timestamp.from(post.createdAt))
                    ps.setTimestamp(11, Timestamp.from(post.updatedAt))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "post_blocks", "post_id", post.id, post.blocks)
                saveTags(conn, post.id, post.tags)
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

    override fun findPost(id: String): Post? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, created_at, updated_at
            FROM content.posts WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapPost(conn, rs) else null }
        }
    }

    override fun listPostsByAuthor(authorId: String, limit: Int): List<Post> = ds.connection.use { conn ->
        listPostsByOwner(OwnerRef(OwnerType.USER, authorId), limit)
    }

    override fun listPostsByOwner(owner: OwnerRef, limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, created_at, updated_at
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
            SELECT id, author_id, owner_type, owner_id, title, text, allow_comments, visibility, status, created_at, updated_at
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
                    INSERT INTO content.comments (id, post_id, author_id, owner_type, owner_id, parent_id, text, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(comment.id))
                    ps.setObject(2, UUID.fromString(comment.postId))
                    ps.setObject(3, UUID.fromString(comment.authorId))
                    ps.setString(4, comment.ownerType.name)
                    ps.setObject(5, UUID.fromString(comment.ownerId))
                    if (comment.parentId == null) ps.setNull(6, Types.OTHER) else ps.setObject(6, UUID.fromString(comment.parentId))
                    ps.setString(7, comment.text)
                    ps.setString(8, comment.status.name)
                    ps.setTimestamp(9, Timestamp.from(comment.createdAt))
                    ps.setTimestamp(10, Timestamp.from(comment.updatedAt))
                    ps.executeUpdate()
                }
                saveBlocks(conn, "comment_blocks", "comment_id", comment.id, comment.blocks)
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

    override fun findComment(id: String): Comment? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, post_id, author_id, owner_type, owner_id, parent_id, text, status, created_at, updated_at
            FROM content.comments WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapComment(conn, rs) else null }
        }
    }

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, post_id, author_id, owner_type, owner_id, parent_id, text, status, created_at, updated_at
            FROM content.comments WHERE post_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.rows { mapComment(conn, rs) } }
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
            tags = loadTags(conn, id),
            allowComments = rs.getBoolean("allow_comments"),
            visibility = Visibility.valueOf(rs.getString("visibility")),
            status = ContentStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

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
            text = rs.getString("text"),
            blocks = loadBlocks(conn, "comment_blocks", "comment_id", id),
            status = ContentStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
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
