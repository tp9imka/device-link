package dev.devicelink.transfer

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceivedFileCommitterTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun staged() = temporary.newFile().apply { writeText("verified contents") }
    private fun destination() = File(temporary.root, "received/file")

    @Test fun `receipt publishes only after file and index exist`() = runTest {
        val source = staged()
        val target = destination()
        var indexed = false
        var published = false
        val success = ReceivedFileCommitter(StandardTestDispatcher(testScheduler)).commit(
            source, target, { true },
            persist = { assertEquals("verified contents", target.readText()); indexed = true },
            removeRecord = { indexed = false },
            publish = { assertTrue(indexed); assertTrue(target.isFile); published = true },
        )
        assertTrue(success)
        assertTrue(published)
        assertFalse(source.exists())
    }

    @Test fun `cancel during index commit cannot resurrect transfer or leave receipt`() = runTest {
        val source = staged()
        val target = destination()
        var current = true
        var indexed = false
        val success = ReceivedFileCommitter(StandardTestDispatcher(testScheduler)).commit(
            source, target, { current },
            persist = { indexed = true; current = false },
            removeRecord = { indexed = false },
            publish = { fail("Cancelled transfer must not become complete") },
        )
        assertFalse(success)
        assertFalse(indexed)
        assertFalse(source.exists())
        assertFalse(target.exists())
    }

    @Test fun `stopped coroutine still rolls back filesystem and committed metadata`() = runTest {
        val source = staged()
        val target = destination()
        var indexed = false
        lateinit var receiving: Job
        receiving = launch {
            ReceivedFileCommitter(StandardTestDispatcher(testScheduler)).commit(
                source, target, { true },
                persist = { indexed = true; receiving.cancel() },
                removeRecord = { indexed = false },
                publish = { fail("Stopped session must not publish receipt") },
            )
        }
        receiving.join()
        assertTrue(receiving.isCancelled)
        assertFalse(indexed)
        assertFalse(source.exists())
        assertFalse(target.exists())
    }

    @Test fun `failed index write rolls back and existing files are never overwritten`() = runTest {
        val committer = ReceivedFileCommitter(StandardTestDispatcher(testScheduler))
        val source = staged()
        val target = destination()
        var rollback = false
        val failure = runCatching {
            committer.commit(source, target, { true },
                persist = { throw IOException("disk full") }, removeRecord = { rollback = true }, publish = { fail() })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(rollback)
        assertFalse(target.exists())
        val anotherSource = staged()
        target.writeText("previous receipt")
        assertTrue(runCatching {
            committer.commit(anotherSource, target, { true }, persist = { fail() }, removeRecord = { fail() }, publish = { fail() })
        }.isFailure)
        assertEquals("previous receipt", target.readText())
    }
}
