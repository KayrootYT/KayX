package com.kayx.manager.feature.filebrowser

import com.kayx.manager.domain.model.SortBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineAssistantParserTest {
    @Test fun parsesSearchAndNavigationCommands() {
        assertEquals(OfflineAssistantCommand.Search("family photos"), OfflineAssistantParser.parse("Search family photos"))
        assertEquals(OfflineAssistantCommand.OpenFolder("Download"), OfflineAssistantParser.parse("open folder Download"))
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
    }

    @Test fun rejectsEmptyOversizedAndUnknownCommands() {
        assertNull(OfflineAssistantParser.parse(""))
        assertNull(OfflineAssistantParser.parse("do something magical"))
        assertNull(OfflineAssistantParser.parse("x".repeat(513)))
    }
}
