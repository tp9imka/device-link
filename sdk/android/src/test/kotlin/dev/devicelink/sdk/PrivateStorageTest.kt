package dev.devicelink.sdk

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivateStorageTest {
    private val base: File = Files.createTempDirectory("dl-storage").toFile().apply { deleteOnExit() }
    private val files = File(base, "files")
    private val noBackup = File(base, "no_backup")

    @Test fun historyAndReceivedFilesMoveOutOfTheBackedUpDirectory() {
        File(files, "devicelink/received/0b9e6f0e-1f7a-4d0c-9c0e-3f1a2b3c4d5e").mkdirs()
        File(files, "devicelink/received/0b9e6f0e-1f7a-4d0c-9c0e-3f1a2b3c4d5e/photo.png").writeText("png")
        File(files, "devicelink/history.json").writeText("[]")

        PrivateStorage.migrate(files, noBackup)

        assertFalse(File(files, "devicelink").exists())
        assertEquals("[]", File(noBackup, "devicelink/history.json").readText())
        assertEquals("png", File(noBackup, "devicelink/received/0b9e6f0e-1f7a-4d0c-9c0e-3f1a2b3c4d5e/photo.png").readText())
    }

    @Test fun newerDataWinsAndOldCopiesAreStillRemoved() {
        File(files, "devicelink").mkdirs()
        File(files, "devicelink/history.json").writeText("old")
        File(noBackup, "devicelink").mkdirs()
        File(noBackup, "devicelink/history.json").writeText("new")
        File(noBackup, "devicelink/links.json").writeText("links")

        PrivateStorage.migrate(files, noBackup)

        assertEquals("new", File(noBackup, "devicelink/history.json").readText())
        assertEquals("links", File(noBackup, "devicelink/links.json").readText())
        assertFalse(File(files, "devicelink").exists())
    }

    @Test fun unrelatedHostFilesAreLeftAlone() {
        File(files, "devicelink").mkdirs()
        File(files, "devicelink/host-owned.txt").writeText("keep")
        File(files, "other.txt").writeText("keep")

        PrivateStorage.migrate(files, noBackup)

        assertEquals("keep", File(files, "devicelink/host-owned.txt").readText())
        assertEquals("keep", File(files, "other.txt").readText())
    }
}
