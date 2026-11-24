import org.avmedia.gshockapi.io.LinkCache
import org.avmedia.gshockapi.utils.Utils
import java.util.Locale

// ============================================================================
// Pure Functional Core: Cache Key Generation
// ============================================================================

/**
 * Pure functional core for cache operations.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles key generation and normalization for cache entries.
 */
object CachedIOFunctional {
    /**
     * Pure generator: Creates normalized cache key from command data.
     *
     * Extracts relevant prefix from compact string command:
     * - For 0x1D, 0x1E, 0x1F, 0x30, 0x31: Uses first 4 characters
     * - For others: Uses first 2 characters
     * All keys are uppercase.
     *
     * No side effects - pure string transformation.
     */
    fun createKey(data: String): String =
        Utils.toCompactString(data)
            .let { shortStr ->
                val startOfData = shortStr.substring(0, 2).uppercase(Locale.getDefault())
                val keyLength = if (startOfData in arrayOf("1D", "1E", "1F", "30", "31")) 4 else 2
                shortStr.substring(0, keyLength).uppercase(Locale.getDefault())
            }
}

/** The single-watch cache: the [LinkCache] of the one link. New code uses `link.cache`. */
object CachedIO {
    private val cache: LinkCache get() = org.avmedia.gshockapi.casio.WatchLink.cache

    fun clearCache() = cache.clear()

    fun remove(key: String) = cache.remove(key)

    suspend fun <T : Any> request(
        key: String,
        compute: suspend (String) -> T,
    ): T = cache.request(key, compute)

    fun set(
        key: String,
        func: () -> Unit,
    ) = cache.set(key, func)

    fun put(
        key: String,
        value: Any,
    ) = cache.put(key, value)

    fun <T : Any> get(key: String): T = cache.get(key)

    fun createKey(data: String): String = CachedIOFunctional.createKey(data)
}
