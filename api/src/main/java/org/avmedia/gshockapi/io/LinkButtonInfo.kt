package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import timber.log.Timber

/**
 * The button info (`0x10`) of one link's watch: which button started the connection and the kind of connection. The
 * request is a command job on this link and only this link's answer completes it. Made only by its [WatchLink].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkButtonInfo internal constructor(private val link: WatchLink, private val cache: LinkCache) {
    companion object {
        /** An answer this old is not shared any more: init asks again. */
        const val SHARE_WINDOW_MS = 30_000L
    }

    private data class State(
        val lastKnownButton: IO.WatchButton = IO.WatchButton.INVALID,
        val connectionKind: Int? = null,
        val deferredResult: CompletableDeferred<IO.WatchButton>? = null,
    )

    @Volatile private var state = State()

    /** The last valid 0x10 answer and when it came. The connection setup and init read the same answer: one read, both use it. */
    private data class Answer(val button: IO.WatchButton, val at: Long)

    @Volatile private var answer: Answer? = null

    @Volatile var clock: () -> Long = System::currentTimeMillis

    /** The longest a button info read waits, behind other jobs and for its answer; a test seam. */
    @Volatile internal var limitMs: Long = LinkAnswer.ASK_LIMIT_MS

    fun forgetAnswer() {
        answer = null
    }

    private fun freshAnswer(): IO.WatchButton? = answer?.takeIf { clock() - it.at in 0..SHARE_WINDOW_MS }?.button

    suspend fun request(): IO.WatchButton {
        Timber.i("Button info request started (0x10)")
        // Always ask the watch: a value cached for an earlier watch must not answer for this one.
        cache.remove("10")
        return cache.request("10") { key ->
            val shared = freshAnswer()
            if (shared != null) {
                // The kind of connection came with that same answer and stays as it was read.
                Timber.i("Button info already received on this link: $shared")
                shared
            } else {
                resetConnectionKind()
                val deferred = CompletableDeferred<IO.WatchButton>()
                synchronized(this) {
                    if (state.deferredResult != null) {
                        Timber.w("BTN_TEST replacing a pending button request; the earlier one can no longer be answered")
                    }
                    state = state.copy(deferredResult = deferred)
                    Timber.i("BTN_TEST request pending=true")
                }
                try {
                    Timber.i("Sending button info request (0x$key)")
                    val sent = link.command(GetSetMode.GET, IOFunctional.toCasioCmd(key))
                    // A request that failed (refused, the link gone) or an answer that never came ends this read.
                    sent.invokeOnCompletion { LinkAnswer.commandFailure(sent)?.let { deferred.completeExceptionally(WatchAnswerFailed("read $key: $it")) } }
                    kotlinx.coroutines.withTimeoutOrNull(limitMs) { deferred.await() }
                        ?: throw WatchAnswerFailed("read $key: no answer within ${limitMs / 1000} s")
                } finally {
                    synchronized(this) {
                        if (state.deferredResult === deferred) {
                            state = state.copy(deferredResult = null)
                        }
                    }
                }
            }
        }.also { button ->
            state = state.copy(lastKnownButton = button)
            Timber.i("Button info request completed: $button")
        }
    }

    fun get(): IO.WatchButton = state.lastKnownButton

    /** The raw kind of connection reported by this watch for the current connection, or null if it has not been read yet. */
    fun getConnectionKind(): Int? = state.connectionKind

    fun resetConnectionKind() {
        synchronized(this) {
            state = state.copy(connectionKind = null)
        }
    }

    fun put(value: Any) {
        cache.put("10", value)
    }

    fun onReceived(data: String) {
        Timber.i("Button info response received: $data")
        synchronized(this) {
            state = state.copy(connectionKind = ButtonPressedIOFunctional.parseConnectionKind(data))
        }
        ButtonPressedIOFunctional.parseButtonPress(data)
            .fold(
                onSuccess = { button ->
                    answer = Answer(button, clock())
                    synchronized(this) {
                        if (state.deferredResult == null) {
                            Timber.w("BTN_TEST reply dropped: no request was pending")
                        }
                        state.deferredResult?.complete(button)
                        state = state.copy(deferredResult = null)
                    }
                },
                onFailure = { error ->
                    Timber.e("Failed to parse button press: ${error.message}")
                    synchronized(this) {
                        state.deferredResult?.complete(IO.WatchButton.INVALID)
                        state = state.copy(deferredResult = null)
                    }
                },
            )
    }
}
