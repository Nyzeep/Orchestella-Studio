package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTerminalShellTest {

    private val theme = ThemeCatalog.constructivistWorkstation

    @Test
    fun bootTranscriptCarriesAnsiAndTheActivePaletteName() {
        val shell = FakeTerminalShell()
        val screen = shell.render(theme)
        assertTrue(screen.contains("\u001B["))
        assertTrue(screen.contains(theme.displayName))
        assertTrue(screen.endsWith("$ "))
    }

    @Test
    fun seededSampleOpensOnACommandAndItsColourStrip() {
        val shell = FakeTerminalShell()
        shell.seedSample(theme)
        val screen = shell.render(theme)

        assertTrue("sample must echo a typed command", screen.contains("theme"))
        assertTrue("sample must show the 16-colour strip", screen.contains("■"))
        assertTrue("seeding must not pollute the user's history", shell.historySnapshot().isEmpty())

        val rows = shell.transcriptSnapshot().size
        shell.seedSample(theme)
        assertEquals("seeding twice must not stack a second copy", rows, shell.transcriptSnapshot().size)
    }

    @Test
    fun helpListsExactlyTheFiveSupportedCommands() {
        val output = FakeTerminalShell().submit("help", theme).output
        listOf("help", "echo", "ls", "theme", "clear").forEach {
            assertTrue("help must mention $it", output.contains(it))
        }
    }

    @Test
    fun echoPrintsItsArgument() {
        assertEquals("hello world", FakeTerminalShell().submit("echo hello world", theme).output)
    }

    @Test
    fun lsListsTheFakeHomeDirectory() {
        val output = FakeTerminalShell().submit("ls", theme).output
        assertTrue(output.contains("Desktop"))
        assertTrue(output.contains("Projects"))
    }

    @Test
    fun themePrintsTheCurrentPaletteNameAndSixteenColourStrip() {
        val shell = FakeTerminalShell()
        val output = shell.submit("theme", theme).output
        assertTrue(output.contains(theme.displayName))
        assertTrue(output.contains("■"))
        assertTrue(output.contains("\u001B[9"))
    }

    @Test
    fun clearDropsTheTranscriptAndTheBanner() {
        val shell = FakeTerminalShell()
        shell.submit("echo old", theme)
        val result = shell.submit("clear", theme)
        assertTrue(result.cleared)
        assertTrue(shell.transcriptSnapshot().isEmpty())
        val screen = shell.render(theme)
        assertFalse(screen.contains("old"))
        assertFalse(screen.contains("Terminal Preview"))
        assertTrue(screen.endsWith("$ "))
    }

    @Test
    fun unknownCommandReportsNotFound() {
        val output = FakeTerminalShell().submit("dance", theme).output
        assertTrue(output.contains("command not found"))
        assertTrue(output.contains("dance"))
    }

    @Test
    fun historyKeepsTheLatestTwentyCommands() {
        val shell = FakeTerminalShell()
        repeat(25) { index -> shell.submit("echo $index", theme) }
        val history = shell.historySnapshot()
        assertEquals(ThemeStudioSpec.terminalHistoryLimit, history.size)
        assertEquals("echo 5", history.first())
        assertEquals("echo 24", history.last())
    }
}
