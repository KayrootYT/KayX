package com.kayx.manager.data.filesystem

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FileOperationJournalTest {
    @Test fun moveUndoSurvivesJournalRecreation() {
        val root = createTempDir(prefix = "kayx-undo-move-")
        try {
            val originalDir = File(root, "old").apply { mkdirs() }
            val newDir = File(root, "new").apply { mkdirs() }
            val original = File(originalDir, "note.txt").apply { writeText("payload") }
            val destination = File(newDir, "note.txt")
            assertTrue(original.renameTo(destination))

            FileOperationJournal(File(root, "private")).recordMove(original, destination)
            val restored = FileOperationJournal(File(root, "private")).undo().getOrThrow()

            assertEquals(FileOperationJournal.Kind.MOVE, restored.kind)
            assertEquals("payload", original.readText())
            assertFalse(destination.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun copyUndoDeletesOnlyUnchangedCopy() {
        val root = createTempDir(prefix = "kayx-undo-copy-")
        try {
            val copied = File(root, "copy.txt").apply { writeText("original") }
            val journal = FileOperationJournal(File(root, "private"))
            assertTrue(journal.recordCopy(copied))
            copied.writeText("user-edited")
            assertTrue(journal.undo().isFailure)
            assertEquals("user-edited", copied.readText())
            assertEquals(1, journal.entries().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun copyUndoRemovesUnmodifiedCopyAndConsumesEntry() {
        val root = createTempDir(prefix = "kayx-undo-copy-ok-")
        try {
            val copied = File(root, "copy.txt").apply { writeText("original") }
            val journal = FileOperationJournal(File(root, "private"))
            assertTrue(journal.recordCopy(copied))
            assertEquals(FileOperationJournal.Kind.COPY, journal.undo().getOrThrow().kind)
            assertFalse(copied.exists())
            assertTrue(journal.entries().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun undoNeverOverwritesRecreatedOriginalPath() {
        val root = createTempDir(prefix = "kayx-undo-conflict-")
        try {
            val source = File(root, "source.txt")
            val destination = File(root, "destination.txt").apply { writeText("moved") }
            FileOperationJournal(File(root, "private")).apply {
                recordMove(source, destination)
            }
            source.writeText("new user data")
            assertTrue(FileOperationJournal(File(root, "private")).undo().isFailure)
            assertEquals("new user data", source.readText())
            assertEquals("moved", destination.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun recoversValidBackupWhenPrimaryJournalIsCorrupt() {
        val root = createTempDir(prefix = "kayx-undo-backup-")
        try {
            val source = File(root, "source.txt")
            val destination = File(root, "destination.txt").apply { writeText("moved") }
            val journalDir = File(root, "private")
            val journal = FileOperationJournal(journalDir)
            journal.recordMove(source, destination)

            val primary = File(journalDir, "file-operations.undo")
            val backup = File(journalDir, "file-operations.undo.bak")
            assertTrue(primary.copyTo(backup).exists())
            primary.writeText("corrupt-primary")

            val recovered = FileOperationJournal(journalDir).entries()

            assertEquals(1, recovered.size)
            assertEquals(FileOperationJournal.Kind.MOVE, recovered.single().kind)
            assertTrue(primary.isFile)
            assertFalse(backup.exists())
        } finally { root.deleteRecursively() }
    }

}
