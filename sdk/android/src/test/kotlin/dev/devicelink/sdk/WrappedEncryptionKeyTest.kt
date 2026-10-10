package dev.devicelink.sdk

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedEncryptionKeyTest {
    private val dir: File = Files.createTempDirectory("dl-keys").toFile().apply { deleteOnExit() }
    private val file = File(dir, "devicelink/encryption.key")
    private val identity: (ByteArray) -> ByteArray = { it }
    private val broken: (ByteArray) -> ByteArray = { throw javax.crypto.AEADBadTagException("tag mismatch") }

    @Test fun firstLaunchCreatesAndReloadsTheSameKey() {
        val created = WrappedEncryptionKey.load(file, hasLinks = false, wrap = identity, unwrap = identity)
        assertFalse(created.lost)
        val reloaded = WrappedEncryptionKey.load(file, hasLinks = true, wrap = identity, unwrap = identity)
        assertFalse(reloaded.lost)
        assertArrayEquals(created.pair.publicKey, reloaded.pair.publicKey)
    }

    @Test fun unreadableKeyIsReportedLostAndTheFileIsKept() {
        WrappedEncryptionKey.load(file, hasLinks = false, wrap = identity, unwrap = identity)
        val before = file.readBytes()
        val loaded = WrappedEncryptionKey.load(file, hasLinks = true, wrap = identity, unwrap = broken)
        assertTrue(loaded.lost)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun unreadableKeyWithoutLinksIsReplacedSinceNoPeerPinnedIt() {
        WrappedEncryptionKey.load(file, hasLinks = false, wrap = identity, unwrap = identity)
        val replaced = WrappedEncryptionKey.load(file, hasLinks = false, wrap = identity, unwrap = broken)
        assertFalse(replaced.lost)
        val reloaded = WrappedEncryptionKey.load(file, hasLinks = true, wrap = identity, unwrap = identity)
        assertArrayEquals(replaced.pair.publicKey, reloaded.pair.publicKey)
    }

    @Test fun missingKeyWhileLinkedIsLostNotSilentlyReplaced() {
        val loaded = WrappedEncryptionKey.load(file, hasLinks = true, wrap = identity, unwrap = identity)
        assertTrue(loaded.lost)
        assertFalse(file.exists())
    }
}
