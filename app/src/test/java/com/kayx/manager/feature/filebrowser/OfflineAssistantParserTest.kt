package com.kayx.manager.feature.filebrowser

import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.data.storage.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineAssistantParserTest {
    @Test fun parsesSearchAndNavigationCommands() {
        assertEquals(OfflineAssistantCommand.Search("family photos"), OfflineAssistantParser.parse("Search family photos"))
        assertEquals(OfflineAssistantCommand.OpenFolder("Download"), OfflineAssistantParser.parse("open folder Download"))
        assertEquals(OfflineAssistantCommand.OpenItem("report.pdf"), OfflineAssistantParser.parse("open report.pdf"))
        assertEquals(OfflineAssistantCommand.SearchContent("invoice"), OfflineAssistantParser.parse("search content invoice"))
        assertEquals(OfflineAssistantCommand.GoBack, OfflineAssistantParser.parse("go back"))
        assertEquals(OfflineAssistantCommand.GoUp, OfflineAssistantParser.parse("upar jao"))
    }

    @Test fun parsesFileManagerCommands() {
        assertEquals(OfflineAssistantCommand.ShowHidden(true), OfflineAssistantParser.parse("show hidden files"))
        assertEquals(OfflineAssistantCommand.Sort(SortBy.SIZE), OfflineAssistantParser.parse("sort by size"))
        assertEquals(OfflineAssistantCommand.SelectAll, OfflineAssistantParser.parse("select all"))
        assertEquals(OfflineAssistantCommand.SelectName("report.pdf"), OfflineAssistantParser.parse("select report.pdf"))
        assertEquals(OfflineAssistantCommand.Copy(true), OfflineAssistantParser.parse("cut"))
        assertEquals(OfflineAssistantCommand.Paste, OfflineAssistantParser.parse("paste here"))
    }

    @Test fun parsesCreateRenameDeleteAndUndo() {
        assertEquals(OfflineAssistantCommand.NewFolder("Work Files"), OfflineAssistantParser.parse("new folder Work Files"))
        assertEquals(OfflineAssistantCommand.NewFile("notes.txt"), OfflineAssistantParser.parse("new file notes.txt"))
        assertEquals(OfflineAssistantCommand.RenameSelected("renamed.pdf"), OfflineAssistantParser.parse("rename selected to renamed.pdf"))
        assertEquals(OfflineAssistantCommand.DeleteSelected, OfflineAssistantParser.parse("delete selected"))
        assertEquals(OfflineAssistantCommand.Undo, OfflineAssistantParser.parse("undo"))
        assertEquals(OfflineAssistantCommand.ZipSelected, OfflineAssistantParser.parse("zip selected"))
        assertEquals(OfflineAssistantCommand.SelectPattern("*.jpg"), OfflineAssistantParser.parse("select pattern *.jpg"))
        assertEquals(OfflineAssistantCommand.Extract("archive.zip"), OfflineAssistantParser.parse("extract archive.zip"))
    }

    @Test fun parsesWholeStorageInventoryCommands() {
        assertEquals(OfflineAssistantCommand.ScanStorage, OfflineAssistantParser.parse("scan storage"))
        assertEquals(OfflineAssistantCommand.ResumeStorageScan, OfflineAssistantParser.parse("resume scan"))
        assertEquals(OfflineAssistantCommand.FindLargeFiles, OfflineAssistantParser.parse("find large files"))
        assertEquals(OfflineAssistantCommand.FindOldFiles, OfflineAssistantParser.parse("purani files dhundo"))
        assertEquals(OfflineAssistantCommand.FindDuplicates, OfflineAssistantParser.parse("find duplicates"))
        assertEquals(OfflineAssistantCommand.CancelStorageScan, OfflineAssistantParser.parse("cancel scan"))
        assertEquals(OfflineAssistantCommand.FindLargeFiles, OfflineAssistantParser.parse("बड़ी फाइलें खोजो"))
    }

    @Test fun parsesCategoryAndFolderSizeCommands() {
        assertEquals(OfflineAssistantCommand.FindCategory(Category.IMAGES), OfflineAssistantParser.parse("find photos"))
        assertEquals(OfflineAssistantCommand.FindCategory(Category.VIDEOS), OfflineAssistantParser.parse("find videos"))
        assertEquals(OfflineAssistantCommand.FindCategory(Category.AUDIO), OfflineAssistantParser.parse("find audio"))
        assertEquals(OfflineAssistantCommand.FindCategory(Category.DOCUMENTS), OfflineAssistantParser.parse("find documents"))
        assertEquals(OfflineAssistantCommand.FindCategory(Category.APKS), OfflineAssistantParser.parse("find apks"))
        assertEquals(OfflineAssistantCommand.FolderSize("Download"), OfflineAssistantParser.parse("folder size Download"))
        assertEquals(OfflineAssistantCommand.FolderSize("DCIM"), OfflineAssistantParser.parse("folder ka size DCIM"))
    }

    @Test fun parsesPreviewFirstOrganizationCommands() {
        assertEquals(
            OfflineAssistantCommand.PreviewOrganize(Category.IMAGES, "/storage/emulated/0/Pictures/Sorted Photos"),
            OfflineAssistantParser.parse("organize photos to /storage/emulated/0/Pictures/Sorted Photos")
        )
        assertEquals(
            OfflineAssistantCommand.PreviewOrganize(Category.DOCUMENTS, "/storage/emulated/0/Documents"),
            OfflineAssistantParser.parse("organize docs to /storage/emulated/0/Documents")
        )
        assertEquals(
            OfflineAssistantCommand.PreviewOrganize(Category.IMAGES, "/storage/emulated/0/Pictures/Sorted", true),
            OfflineAssistantParser.parse("move images to /storage/emulated/0/Pictures/Sorted")
        )
        assertNull(OfflineAssistantParser.parse("organize everything to /storage/emulated/0"))
        assertNull(OfflineAssistantParser.parse("organize images"))
    }

    @Test fun parsesStorageToolNavigationCommands() {
        assertEquals(OfflineAssistantCommand.OpenRecycleBin, OfflineAssistantParser.parse("open recycle bin"))
        assertEquals(OfflineAssistantCommand.OpenTags, OfflineAssistantParser.parse("tags kholo"))
        assertEquals(OfflineAssistantCommand.OpenPrivateSpace, OfflineAssistantParser.parse("open private space"))
        assertEquals(OfflineAssistantCommand.TagSelected, OfflineAssistantParser.parse("tag selected"))
        assertEquals(OfflineAssistantCommand.OpenNetworkStorage, OfflineAssistantParser.parse("open network storage"))
        assertEquals(OfflineAssistantCommand.InspectApk("system.apk"), OfflineAssistantParser.parse("inspect apk system.apk"))
        assertEquals(OfflineAssistantCommand.BrowseArchive("backup.zip"), OfflineAssistantParser.parse("browse archive backup.zip"))
    }

    @Test fun rejectsEmptyOversizedAndUnknownCommands() {
        assertNull(OfflineAssistantParser.parse(""))
        assertNull(OfflineAssistantParser.parse("do something magical"))
        assertNull(OfflineAssistantParser.parse("x".repeat(513)))
        assertEquals(OfflineAssistantCommand.GoBack, OfflineAssistantParser.parse("वापस जाओ"))
        assertEquals(OfflineAssistantCommand.ShowHidden(true), OfflineAssistantParser.parse("छिपी फाइलें दिखाओ"))
    }
}
