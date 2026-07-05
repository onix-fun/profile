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
                    INSERT INTO content.posts (id, author_id, title, text, visibility, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(post.id))
                    ps.setObject(2, UUID.fromString(post.authorId))
                    ps.setString(3, post.title)
                    ps.setString(4, post.text)
                    ps.setString(5, post.visibility.name)
                    ps.setString(6, post.status.name)
                    ps.setTimestamp(7, Timestamp.from(post.createdAt))
                    ps.setTimestamp(8, Timestamp.from(post.updatedAt))
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
            SELECT id, author_id, title, text, visibility, status, created_at, updated_at
            FROM content.posts WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapPost(conn, rs) else null }
        }
    }

    override fun listPostsByAuthor(authorId: String, limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, title, text, visibility, status, created_at, updated_at
            FROM content.posts WHERE author_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(authorId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun listRecentPosts(limit: Int): List<Post> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, author_id, title, text, visibility, status, created_at, updated_at
            FROM content.posts WHERE status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> rs.rows { mapPost(conn, rs) } }
        }
    }

    override fun saveStory(story: Story): Story {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.stories (id, author_id, visibility, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(story.id))
                    ps.setObject(2, UUID.fromString(story.authorId))
                    ps.setString(3, story.visibility.name)
                    ps.setString(4, story.status.name)
                    ps.setTimestamp(5, Timestamp.from(story.createdAt))
                    ps.setTimestamp(6, Timestamp.from(story.expiresAt))
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
            SELECT id, author_id, visibility, status, created_at, expires_at
            FROM content.stories WHERE id = ? AND status = 'ACTIVE' AND expires_at > ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.setTimestamp(2, Timestamp.from(Instant.now()))
            ps.executeQuery().use { rs -> if (rs.next()) mapStory(conn, rs) else null }
        }
    }

    override fun listActiveStories(now: Instant, limit: Int): List<Story> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, author_id, visibility, status, created_at, expires_at
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
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, author_id, visibility, status, created_at, expires_at
                FROM content.stories
                WHERE author_id = ? AND status = 'ACTIVE' AND expires_at > ?
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.fromString(authorId))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setInt(3, limit)
                ps.executeQuery().use { rs -> rs.rows { mapStory(conn, rs) } }
            }
        }

    override fun saveComment(comment: Comment): Comment {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO content.comments (id, post_id, author_id, parent_id, text, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.fromString(comment.id))
                    ps.setObject(2, UUID.fromString(comment.postId))
                    ps.setObject(3, UUID.fromString(comment.authorId))
                    if (comment.parentId == null) ps.setNull(4, Types.OTHER) else ps.setObject(4, UUID.fromString(comment.parentId))
                    ps.setString(5, comment.text)
                    ps.setString(6, comment.status.name)
                    ps.setTimestamp(7, Timestamp.from(comment.createdAt))
                    ps.setTimestamp(8, Timestamp.from(comment.updatedAt))
                    ps.executeUpdate()
                }
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
            SELECT id, post_id, author_id, parent_id, text, status, created_at, updated_at
            FROM content.comments WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs -> if (rs.next()) mapComment(rs) else null }
        }
    }

    override fun listCommentsForPost(postId: String, limit: Int): List<Comment> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, post_id, author_id, parent_id, text, status, created_at, updated_at
            FROM content.comments WHERE post_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.fromString(postId))
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.rows { mapComment(rs) } }
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
            title = rs.getString("title"),
            text = rs.getString("text"),
            blocks = loadBlocks(conn, "post_blocks", "post_id", id),
            tags = loadTags(conn, id),
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
            blocks = loadBlocks(conn, "story_blocks", "story_id", id),
            visibility = Visibility.valueOf(rs.getString("visibility")),
            status = ContentStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant()
        )
    }

    private fun mapComment(rs: ResultSet): Comment = Comment(
        id = rs.getObject("id").toString(),
        postId = rs.getObject("post_id").toString(),
        authorId = rs.getObject("author_id").toString(),
        parentId = rs.getObject("parent_id")?.toString(),
        text = rs.getString("text"),
        status = ContentStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant()
    )

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

    private fun <T> ResultSet.rows(mapper: () -> T): List<T> {
        val items = mutableListOf<T>()
        while (next()) items.add(mapper())
        return items
    }
}
