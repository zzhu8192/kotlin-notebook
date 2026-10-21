package kotnb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CellType {
    @SerialName("kotlin")
    KOTLIN,

    @SerialName("markdown")
    MARKDOWN,
}

@Serializable
data class Cell(val type: CellType, val source: String)

/**
 * Reads and writes `.kot` notebooks, modeled on the Databricks Scala notebook source format:
 *
 * ```
 * // Kotlin notebook source
 * val x = 1
 *
 * // COMMAND ----------
 *
 * // MAGIC %md
 * // MAGIC # A markdown cell
 * ```
 *
 * Kotlin cells are written verbatim. A Kotlin cell that would otherwise be ambiguous (it contains a separator line,
 * or every line looks like a `// MAGIC` line) is written as a `// MAGIC %kotlin` cell instead, so every notebook
 * round-trips exactly, apart from leading and trailing blank lines in a cell.
 */
object NotebookFormat {
    const val HEADER = "// Kotlin notebook source"
    const val SEPARATOR = "// COMMAND ----------"
    private const val MAGIC = "// MAGIC"
    private const val MARKDOWN_MAGIC = "%md"
    private const val KOTLIN_MAGIC = "%kotlin"

    fun serialize(cells: List<Cell>): String = buildString {
        append(HEADER).append('\n')
        cells.forEachIndexed { index, cell ->
            if (index > 0) append('\n').append('\n').append(SEPARATOR).append('\n').append('\n')
            append(encode(cell))
        }
        if (cells.isNotEmpty()) append('\n')
    }

    fun parse(text: String): List<Cell> {
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        // Also accepts "// Databricks notebook source", so Databricks Scala exports open as-is.
        if (lines.first().trimEnd().endsWith("notebook source")) lines.removeAt(0)

        val chunks = mutableListOf(mutableListOf<String>())
        for (line in lines) {
            if (line.trimEnd() == SEPARATOR) chunks += mutableListOf<String>() else chunks.last() += line
        }
        val cells = chunks.map { decode(it.trimBlankLines()) }
        return if (cells.size == 1 && cells[0].source.isEmpty() && cells[0].type == CellType.KOTLIN) emptyList() else cells
    }

    private fun encode(cell: Cell): String {
        val lines = cell.source.replace("\r\n", "\n").split('\n')
        return when {
            cell.type == CellType.MARKDOWN -> magic(MARKDOWN_MAGIC, lines)
            lines.any { it.trimEnd() == SEPARATOR } || lines.all(::isMagicLine) -> magic(KOTLIN_MAGIC, lines)
            else -> cell.source
        }
    }

    private fun magic(command: String, lines: List<String>): String {
        val body = if (lines == listOf("")) emptyList() else lines
        return (listOf(command) + body).joinToString("\n") { if (it.isEmpty()) MAGIC else "$MAGIC $it" }
    }

    private fun decode(lines: List<String>): Cell {
        if (lines.isEmpty() || !lines.all(::isMagicLine)) return Cell(CellType.KOTLIN, lines.joinToString("\n"))

        val body = lines.map { it.removePrefix(MAGIC).removePrefix(" ") }
        val command = body[0].substringBefore(' ')
        // Databricks allows content on the command line itself, e.g. "// MAGIC %md # Title".
        val firstLine = body[0].substringAfter(' ', "")
        val content = (listOfNotNull(firstLine.ifEmpty { null }) + body.drop(1)).joinToString("\n")
        return when (command) {
            MARKDOWN_MAGIC -> Cell(CellType.MARKDOWN, content)
            KOTLIN_MAGIC -> Cell(CellType.KOTLIN, content)
            // Unknown magics (e.g. %python from a Databricks export) are kept verbatim rather than dropped.
            else -> Cell(CellType.KOTLIN, lines.joinToString("\n"))
        }
    }

    private fun isMagicLine(line: String) = line == MAGIC || line.startsWith("$MAGIC ")

    private fun List<String>.trimBlankLines(): List<String> =
        dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
}
