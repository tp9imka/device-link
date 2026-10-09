package dev.devicelink.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ResourceHandoffTest {
    @Test fun `descriptor acquired on IO is closed if dispatcher return is cancelled`() = runBlocking {
        var closes = 0
        val ownership = ResourceHandoff<String> { closes++ }
        val job = launch {
            try {
                withContext(Dispatchers.IO) {
                    ownership.acquire("descriptor")
                    currentCoroutineContext().cancel()
                    "prepared image"
                }
                fail("Cancelled preparation must not hand off its descriptor")
            } finally { ownership.close() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, closes)
        ownership.close()
        assertEquals(1, closes)
    }

    @Test fun `transferred descriptor remains owned by outgoing transfer`() {
        var closes = 0
        val ownership = ResourceHandoff<String> { closes++ }
        ownership.acquire("descriptor")
        ownership.release()
        ownership.close()
        assertEquals(0, closes)
    }
}
