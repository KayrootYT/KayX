package com.kayx.manager.feature.filebrowser

import com.kayx.manager.data.storage.Category
import com.kayx.manager.data.storage.OfflineStorageInventory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class OfflineOrganizationPlannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun createsLocalOnlyPreviewAndPreservesSourceFiles() {
        val source = temporaryFolder.newFolder("source")
        val destination = temporaryFolder.newFolder("destination")
        val photo = File(source, "photo.jpg").apply { writeText("photo-data") }
        val document = File(source, "notes.pdf").apply { writeText("document-data") }
        val entries = listOf(photo, document).map { file ->
            OfflineStorageInventory.Entry(
                key = file.absolutePath,
                path = file.absolutePath,
                name = file.name,
                size = file.length(),
                modified = file.lastModified(),
                category = if (file.extension == "jpg") Category.IMAGES else Category.DOCUMENTS,
                saf = false
            )
        } + OfflineStorageInventory.Entry(
            key = "content://provider/document/1",
            path = "content://provider/document/1",
            name = "cloud-photo.jpg",
            size = 10,
            modified = 1,
            category = Category.IMAGES,
            saf = true
        )

        val plan = OfflineOrganizationPlanner.create(entries, Category.IMAGES, destination.absolutePath).getOrThrow()

        assertEquals(listOf(photo.absolutePath), plan.files.map { it.path })
        assertEquals(photo.length(), plan.totalBytes)
        assertTrue(photo.exists())
        assertTrue(OfflineOrganizationPlanner.isStillValid(plan))
    }

    @Test fun rejectsInvalidDestinationAndStaleSource() {
        val source = temporaryFolder.newFolder("source")
        val destination = temporaryFolder.newFolder("destination")
        val photo = File(source, "photo.jpg").apply { writeText("before") }
        val entry = OfflineStorageInventory.Entry(
            key = photo.absolutePath,
            path = photo.absolutePath,
            name = photo.name,
            size = photo.length(),
            modified = photo.lastModified(),
            category = Category.IMAGES,
            saf = false
        )

        assertTrue(OfflineOrganizationPlanner.create(listOf(entry), Category.IMAGES, source.absolutePath).isFailure)
        val plan = OfflineOrganizationPlanner.create(listOf(entry), Category.IMAGES, destination.absolutePath).getOrThrow()
        photo.writeText("changed content")
        assertFalse(OfflineOrganizationPlanner.isStillValid(plan))
    }

    @Test fun refusesMoreThanOneHundredFiles() {
        val source = temporaryFolder.newFolder("source")
        val destination = temporaryFolder.newFolder("destination")
        val entries = (1..101).map { index ->
            val file = File(source, "photo$index.jpg").apply { writeText("x") }
            OfflineStorageInventory.Entry(file.absolutePath, file.absolutePath, file.name, file.length(),
                file.lastModified(), Category.IMAGES, saf = false)
        }
        assertTrue(OfflineOrganizationPlanner.create(entries, Category.IMAGES, destination.absolutePath).isFailure)
    }
}
