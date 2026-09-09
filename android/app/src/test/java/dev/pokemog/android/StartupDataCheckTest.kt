package dev.pokemog.android

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class StartupDataCheckTest {
    @Test fun triggerOnlySchedulesAndDeduplicatesBeforeWorkerStarts() {
        val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var preferenceReads = 0
        val scheduler = StartupDataCheck(scope) { preferenceReads++ }
        try {
            repeat(100) { scheduler.trigger() }
            assertEquals(0, preferenceReads)
            assertEquals(1, queue.size)
            queue.remove().run()
            assertEquals(1, preferenceReads)
            scheduler.trigger()
            assertEquals(1, queue.size)
            queue.remove().run()
            assertEquals(2, preferenceReads)
        } finally { scope.cancel() }
    }

    @Test(timeout = 10000) fun callersNeverWaitForSnapshotMonitorAndConcurrentTriggersShareOneCheck() {
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(4)
        val dispatcher = worker.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val snapshotMonitor = Any()
        val started = CountDownLatch(1)
        val checks = AtomicInteger()
        val scheduler = StartupDataCheck(scope) {
            checks.incrementAndGet()
            started.countDown()
            synchronized(snapshotMonitor) { /* Simulated cached validation owned by another IO task. */ }
        }
        try {
            synchronized(snapshotMonitor) {
                callers.submit { scheduler.trigger() }.get(2, TimeUnit.SECONDS)
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val triggers = List(100) { callers.submit { scheduler.trigger() } }
                triggers.forEach { it.get(2, TimeUnit.SECONDS) }
                assertEquals(1, checks.get())
            }
            worker.submit {}.get(2, TimeUnit.SECONDS)
            scheduler.trigger()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(2, checks.get())
        } finally { scope.cancel(); dispatcher.close(); callers.shutdownNow() }
    }

    @Test fun cancelledQueuedJobReleasesGateWithoutRunningCheck() {
        val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var checks = 0
        val scheduler = StartupDataCheck(scope) { checks++ }
        scheduler.trigger()
        scope.cancel()
        queue.remove().run()
        scheduler.trigger()
        assertEquals(1, queue.size)
        queue.remove().run()
        assertEquals(0, checks)
    }
}
