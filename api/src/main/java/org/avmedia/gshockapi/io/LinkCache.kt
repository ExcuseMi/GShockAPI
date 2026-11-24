package org.avmedia.gshockapi.io

/** The answers one link's watch gave, by key ("10", "1D00"): another watch never gets them. Made only by its link. */
class LinkCache internal constructor() {
    private data class State(
        val cache: Map<String, Any?> = emptyMap(),
        val cacheOff: Boolean = false,
    )

    @Volatile private var state = State()

    fun clear() {
        state = state.copy(cache = emptyMap())
    }

    fun remove(key: String) {
        state = state.copy(cache = state.cache - key.uppercase())
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> request(
        key: String,
        compute: suspend (String) -> T,
    ): T =
        when {
            state.cacheOff ->
                compute(key).also { value ->
                    state = state.copy(cache = state.cache + (key.uppercase() to value))
                }

            else ->
                state.cache[key.uppercase()]?.let { it as T } ?: compute(key).also { value ->
                    state = state.copy(cache = state.cache + (key.uppercase() to value))
                }
        }

    fun set(
        key: String,
        func: () -> Unit,
    ) {
        func()
        remove(key)
    }

    fun put(
        key: String,
        value: Any,
    ) {
        state = state.copy(cache = state.cache + (key.uppercase() to value))
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T =
        state.cache[key.uppercase()]?.let { it as T }
            ?: throw IllegalStateException("Key $key not found in cache")
}
