package org.avmedia.gshockapi.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

internal fun spec(
    name: String,
    priority: JobPriority,
    deadlineMs: Long = 10_000,
    rule: OnDisconnect = OnDisconnect.WAIT,
) = JobSpec(name, JobSource.System("test"), priority, deadlineMs, rule)

internal fun testLinks() = WatchLinks(CoroutineScope(SupervisorJob() + Dispatchers.Default))

/** A link that went through its setup and is ready. */
internal suspend fun readyLink(links: WatchLinks, key: String, gatt: FakeGatt): WatchLink {
    val link = links.open(key, gatt)
    check(link.onGattReady().await().ok)
    check(link.state.value == LinkState.READY)
    return link
}

internal suspend fun waitUntil(what: String, ms: Long = 3_000, cond: () -> Boolean) {
    try {
        withTimeout(ms) { while (!cond()) delay(2) }
    } catch (e: Exception) {
        throw AssertionError("timed out waiting for $what", e)
    }
}
