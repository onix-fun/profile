package com.onix.content.service

import java.time.Duration
import com.onix.content.media.MediaClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Fallback source-of-truth reconciler.  Media lifecycle events can wake this
 * work up earlier, but publication never depends on an open browser tab or a
 * subscription being delivered.
 */
class PublicationReconciliationWorker(
    private val content: ContentService,
    private val media: MediaClient,
    private val interval: Duration = Duration.ofSeconds(5),
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : AutoCloseable {
    fun start(): PublicationReconciliationWorker {
        executor.scheduleWithFixedDelay(
            {
                runCatching { media.listAssetLifecycleEvents(content.mediaLifecycleCursor()) }
                    .onSuccess(content::consumeMediaLifecycleEvents)
                runCatching { content.reconcilePendingPublications() }
                    .onFailure { error ->
                        System.err.println("publication reconciliation failed: ${error::class.simpleName}: ${error.message}")
                    }
            },
            interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS
        )
        return this
    }

    override fun close() {
        executor.shutdownNow()
    }
}
