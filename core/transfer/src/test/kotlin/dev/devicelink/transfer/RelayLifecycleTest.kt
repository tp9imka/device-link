package dev.devicelink.transfer

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayLifecycleTest {
    @Test fun `receipt or failure cancels expiry jobs and frees capacity immediately`() = runTest {
        val outgoing = RelayOutgoingTracker(this) { testScheduler.currentTime }
        repeat(20) { index -> assertTrue(outgoing.track("$index", "peer", 86_400_000) { fail("Completed transfer expired") }) }
        runCurrent()
        assertFalse(outgoing.track("overflow", "peer", 86_400_000) {})
        outgoing.remove("0")
        assertNull(outgoing.recipient("0"))
        assertTrue(outgoing.track("replacement", "peer", 86_400_000) { fail("Completed transfer expired") })
        outgoing.idsForPeer("peer").forEach(outgoing::remove)
        assertEquals(0, outgoing.size)
        runCurrent()
        assertFalse(coroutineContext[kotlinx.coroutines.Job]!!.children.any { it.isActive })
    }

    @Test fun `expiry drops association once and never fails a completed transfer`() = runTest {
        val outgoing = RelayOutgoingTracker(this) { testScheduler.currentTime }
        val expired = mutableListOf<String>()
        outgoing.track("expires", "alice", 1000) { expired.add("expires") }
        outgoing.track("completed", "bob", 1000) { expired.add("completed") }
        outgoing.remove("completed")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("expires"), expired)
        assertEquals(0, outgoing.size)
    }

    @Test fun `failed or offline revocation survives reconstruction and only clears after successful delete`() = runTest {
        val durable = mutableMapOf("https://one" to mutableSetOf("alice"), "https://two" to mutableSetOf("bob"))
        fun reconciler() = RelayAclReconciler({ durable[it]?.toSet().orEmpty() }, { url, id -> durable[url]!!.remove(id) })
        reconciler().revokePending("https://one", { false }) { fail("Off must not write ACL") }
        try {
            reconciler().revokePending("https://one", { true }) { throw IOException("offline") }
            fail("Request must fail")
        } catch (_: IOException) { }
        assertEquals(setOf("alice"), durable["https://one"])
        val deletes = mutableListOf<String>()
        reconciler().revokePending("https://one", { true }) { deletes.add(it) }
        assertEquals(listOf("alice"), deletes)
        assertTrue(durable["https://one"]!!.isEmpty())
        assertEquals(setOf("bob"), durable["https://two"])
    }

    @Test fun `revocation waits for prior grant and queued stale grant cannot restore access`() = runTest {
        val durable = mutableSetOf("peer")
        val writes = mutableListOf<String>()
        val granted = CompletableDeferred<Unit>()
        val releaseGrant = CompletableDeferred<Unit>()
        var trusted = true
        val acl = RelayAclReconciler({ durable.toSet() }, { _, id -> durable.remove(id) })
        val first = launch { acl.grant({ trusted }) {
            granted.complete(Unit); releaseGrant.await(); writes.add("PUT")
        } }
        granted.await()
        trusted = false
        val revoke = launch { acl.revokePending("https://relay", { true }) { writes.add("DELETE") } }
        val staleGrant = launch { acl.grant({ trusted }) { writes.add("STALE PUT") } }
        runCurrent()
        assertTrue(writes.isEmpty())
        releaseGrant.complete(Unit)
        first.join(); revoke.join(); staleGrant.join()
        assertEquals(listOf("PUT", "DELETE"), writes)
        assertTrue(durable.isEmpty())
    }
}
