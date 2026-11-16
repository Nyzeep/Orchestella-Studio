package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.AppTheme

/** One local command's effect; no PTY, VM or shell process is involved. */
data class FakeShellResult(
    val output: String,
    val cleared: Boolean = false,
)

/**
 * The five-command fake shell behind the Theme Studio terminal preview.
 *
 * It is deliberately a plain JVM object: the preview's transcript can be
 * replayed after every palette change, and the command contract is tested
 * without composing Android views. History is capped at 20 lines.
 */
class FakeTerminalShell {

    private val transcript = mutableListOf<String>()
    private val history = ArrayDeque<String>()
    private var showBanner = true

    fun submit(raw: String, theme: AppTheme): FakeShellResult {
        val line = raw.trim()
        if (line.isEmpty()) return FakeShellResult(output = "")
        remember(line)

        val rawCommand = line.substringBefore(' ')
        val command = rawCommand.lowercase()
        val argument = line.substringAfter(' ', "").trimStart()
        val result = when (command) {
            "help" -> FakeShellResult(helpText())
            "echo" -> FakeShellResult(argument)
            "ls" -> FakeShellResult(directoryListing())
            "theme" -> FakeShellResult(themeText(theme))
            "clear" -> {
                transcript.clear()
                showBanner = false
                FakeShellResult(output = "", cleared = true)
            }
            else -> FakeShellResult("$rawCommand: command not found")
        }

        if (result.cleared) return result

        transcript += "${prompt()}$line"
        if (result.output.isNotEmpty()) transcript += result.output.lines()
        trimTranscript()
        return result
    }

    /** Full screen repaint, including the boot banner and the trailing prompt. */
    fun render(theme: AppTheme): String {
        val builder = StringBuilder()
        if (showBanner) {
            builder.append(banner(theme)).append("\r\n")
        }
        if (transcript.isNotEmpty()) {
            builder.append(transcript.joinToString("\r\n")).append("\r\n")
        }
        builder.append(prompt())
        return builder.toString()
    }

    /** Theme changes restart the sample screen; history survives. */
    fun resetForTheme() {
        transcript.clear()
        showBanner = true
    }

    fun historySnapshot(): List<String> = history.toList()

    fun transcriptSnapshot(): List<String> = transcript.toList()

    private fun remember(line: String) {
        if (history.lastOrNull() == line) return
        if (history.size == ThemeStudioSpec.terminalHistoryLimit) history.removeFirst()
        history.addLast(line)
    }

    private fun trimTranscript() {
        while (transcript.size > MAX_TRANSCRIPT_LINES) transcript.removeAt(0)
    }

    private fun prompt(): String = "\u001B[32muser@orchestella\u001B[0m:\u001B[34m~\u001B[0m$ "

    private fun banner(theme: AppTheme): String = listOf(
        "\u001B[1;36mOrchestella\u001B[0m \u001B[90mTerminal Preview\u001B[0m",
        "\u001B[32m✓\u001B[0m 80×24 · local fake shell · no PTY / VM",
        "\u001B[33mtheme\u001B[0m ${theme.displayName}   \u001B[35mhelp\u001B[0m for commands",
    ).joinToString("\r\n")

    private fun helpText(): String = listOf(
        "\u001B[36mhelp\u001B[0m          show this list",
        "\u001B[36mecho\u001B[0m <text>   print text",
        "\u001B[36mls\u001B[0m            list the fake home directory",
        "\u001B[36mtheme\u001B[0m         print the active palette",
        "\u001B[36mclear\u001B[0m         clear the preview screen",
    ).joinToString("\r\n")

    private fun directoryListing(): String = listOf(
        "\u001B[34mDesktop\u001B[0m  \u001B[34mDocuments\u001B[0m  \u001B[34mDownloads\u001B[0m  \u001B[34mMusic\u001B[0m",
        "\u001B[34mPictures\u001B[0m  \u001B[34mProjects\u001B[0m  \u001B[34mPublic\u001B[0m  \u001B[34mVideos\u001B[0m",
    ).joinToString("\r\n")

    /** Prints the palette name plus a 16-colour strip, so a theme switch is visible. */
    private fun themeText(theme: AppTheme): String {
        val normal = (0..7).joinToString("") { "\u001B[3${it}m■" }
        val bright = (0..7).joinToString("") { "\u001B[9${it}m■" }
        return buildString {
            append("\u001B[1m").append(theme.displayName).append("\u001B[0m")
            append(" · ").append(if (theme.isDark) "暗" else "亮")
            if (theme.isFixed) append(" · 固定")
            append("\r\n").append(normal).append("\u001B[0m\r\n")
            append(bright).append("\u001B[0m")
        }
    }

    private companion object {
        const val MAX_TRANSCRIPT_LINES = 240
    }
}
