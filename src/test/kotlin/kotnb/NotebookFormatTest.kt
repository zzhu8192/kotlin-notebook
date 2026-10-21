package kotnb

import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookFormatTest {
    private fun roundTrip(cells: List<Cell>) = assertEquals(cells, NotebookFormat.parse(NotebookFormat.serialize(cells)))

    @Test
    fun `writes the Databricks style layout`() {
        val text = NotebookFormat.serialize(
            listOf(
                Cell(CellType.KOTLIN, "val x = 1\nprintln(x)"),
                Cell(CellType.MARKDOWN, "# Title\n\nSome *text*"),
                Cell(CellType.KOTLIN, "x"),
            ),
        )
        assertEquals(
            """
            // Kotlin notebook source
            val x = 1
            println(x)

            // COMMAND ----------

            // MAGIC %md
            // MAGIC # Title
            // MAGIC
            // MAGIC Some *text*

            // COMMAND ----------

            x

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `round trips ordinary notebooks`() {
        roundTrip(emptyList())
        roundTrip(listOf(Cell(CellType.KOTLIN, "1 + 1")))
        roundTrip(listOf(Cell(CellType.KOTLIN, ""), Cell(CellType.KOTLIN, "x"), Cell(CellType.KOTLIN, "")))
        roundTrip(listOf(Cell(CellType.MARKDOWN, ""), Cell(CellType.MARKDOWN, "\nstarts blank\n\nends blank\n")))
        roundTrip(listOf(Cell(CellType.KOTLIN, "fun f() {\n\n    // a comment\n    return\n}")))
    }

    @Test
    fun `escapes Kotlin cells that look like notebook syntax`() {
        val tricky = listOf(
            Cell(CellType.KOTLIN, "val a = 1\n// COMMAND ----------\nval b = 2"),
            Cell(CellType.KOTLIN, "// MAGIC %md\n// MAGIC not markdown"),
            Cell(CellType.KOTLIN, "// MAGIC"),
            Cell(CellType.MARKDOWN, "// COMMAND ----------"),
        )
        roundTrip(tricky)
        val text = NotebookFormat.serialize(tricky.take(1))
        assertEquals("// Kotlin notebook source\n// MAGIC %kotlin\n// MAGIC val a = 1\n// MAGIC // COMMAND ----------\n// MAGIC val b = 2\n", text)
    }

    @Test
    fun `reads Databricks exports and Windows line endings`() {
        val text = "// Databricks notebook source\r\nval x = 1\r\n\r\n// COMMAND ----------\r\n\r\n// MAGIC %md # Heading\r\n// MAGIC body\r\n\r\n" +
            "// COMMAND ----------\r\n\r\n// MAGIC %python\r\n// MAGIC print(1)\r\n"
        assertEquals(
            listOf(
                Cell(CellType.KOTLIN, "val x = 1"),
                Cell(CellType.MARKDOWN, "# Heading\nbody"),
                Cell(CellType.KOTLIN, "// MAGIC %python\n// MAGIC print(1)"),
            ),
            NotebookFormat.parse(text),
        )
    }

    @Test
    fun `reads files without a header`() {
        assertEquals(listOf(Cell(CellType.KOTLIN, "println(1)")), NotebookFormat.parse("println(1)\n"))
        assertEquals(emptyList(), NotebookFormat.parse(""))
    }
}
