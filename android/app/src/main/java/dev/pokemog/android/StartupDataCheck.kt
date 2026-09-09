package dev.pokemog.android

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The supplied scope owns the worker dispatcher; callers only enqueue, never perform check work. */
internal class StartupDataCheck(private val scope: CoroutineScope, private val check: suspend () -> Unit) {
    private val pending = AtomicBoolean(false)

    fun trigger() {
        if (!pending.compareAndSet(false, true)) return
        try {
            scope.launch { check() }.invokeOnCompletion { pending.set(false) }
        } catch (error: Throwable) {
            pending.set(false)
            throw error
        }
    }
}
