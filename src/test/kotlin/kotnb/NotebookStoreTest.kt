package kotnb

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookStoreTest {
    private val root = Files.createTempDirectory("kotnb-store")
    private val store = NotebookStore(root)
    private val cells = listOf(Cell(CellType.KOTLIN, "1"))

    @Test
    fun `saves notebooks as kot files`() {
        store.save("abc", cells)
        assertEquals("// Kotlin notebook source\n1\n", root.resolve("abc.kot").readText())
        assertEquals(cells, store.load("abc.kot"))
        store.save("team/report", cells)
        assertTrue(root.resolve("team/report.kot").exists())
        assertEquals(listOf("abc", "team/report"), store.list())
    }

    @Test
    fun `create, rename and delete`() {
        store.create("one", cells)
        assertFailsWith<NotebookExistsException> { store.create("one", cells) }
        store.rename("one", "two")
        assertFalse(store.exists("one"))
        assertEquals(cells, store.load("two"))
        store.delete("two")
        assertFailsWith<NotebookNotFoundException> { store.load("two") }
        assertFailsWith<NotebookNotFoundException> { store.delete("two") }
    }

    @Test
    fun `rejects names that escape the notebook directory`() {
        for (bad in listOf("", "../x", "a/../../x", ".hidden", "a\b", "C:x", "nul", "a//b", " a/ b")) {
            assertFailsWith<InvalidNotebookNameException>(bad) { store.save(bad, cells) }
        }
        assertEquals("My notebook (2)", NotebookStore.normalizeName(" My notebook (2).kot "))
    }
}
