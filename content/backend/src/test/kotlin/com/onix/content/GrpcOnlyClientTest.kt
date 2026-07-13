package com.onix.content

import com.onix.content.media.MediaClient
import com.onix.content.media.MediaUnavailable
import com.onix.content.search.HttpSearchIndexClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrpcOnlyClientTest {
    @Test
    fun `search client reports missing grpc target without http fallback`() {
        val result = HttpSearchIndexClient(null, "secret").search("posts", "query", 10)

        assertEquals("Search index gRPC target is not configured", result.error)
    }

    @Test
    fun `media client reports missing grpc target without http fallback`() {
        val client = MediaClient(null, "secret")

        assertFailsWith<MediaUnavailable> {
            client.downloadUrl("blob-1")
        }
    }
}
