package kotnb

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

class NotebookNotFoundException(name: String) : RuntimeException("Notebook '$name' does not exist")
class NotebookExistsException(name: String) : RuntimeException("Notebook '$name' already exists")
class InvalidNotebookNameException(message: String) : RuntimeException(message)

/**
 * Stores notebooks as `<root>/<name>.kot`. Names may contain `/` to address subdirectories,
 * e.g. `team/report` is stored at `<root>/team/report.kot`.
 */
class NotebookStore(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()

    init {
        root.createDirectories()
    }

    fun list(): List<String> =
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(EXTENSION) }
                .map { root.relativize(it).invariantSeparatorsPathString.removeSuffix(EXTENSION) }
                .filter { runCatching { normalizeName(it) }.isSuccess }
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }

    fun exists(name: String): Boolean = pathOf(name).isRegularFile()

    fun load(name: String): List<Cell> {
        val path = pathOf(name)
        if (!path.isRegularFile()) throw NotebookNotFoundException(name)
        return NotebookFormat.parse(path.readText())
    }

    fun save(name: String, cells: List<Cell>) = write(pathOf(name), NotebookFormat.serialize(cells))

    fun create(name: String, cells: List<Cell>) {
        val path = pathOf(name)
        if (path.exists()) throw NotebookExistsException(name)
        write(path, NotebookFormat.serialize(cells))
    }

    fun delete(name: String) {
        if (!pathOf(name).deleteIfExists()) throw NotebookNotFoundException(name)
    }

    fun rename(from: String, to: String) {
        val source = pathOf(from)
        val target = pathOf(to)
        if (!source.isRegularFile()) throw NotebookNotFoundException(from)
        if (target.exists()) throw NotebookExistsException(to)
        target.parent.createDirectories()
        source.moveTo(target)
    }

    private fun pathOf(name: String): Path = root.resolve(normalizeName(name) + EXTENSION)

    private fun write(path: Path, text: String) {
        path.parent.createDirectories()
        // Write to a sibling temp file and move it into place, so a crash never leaves a half-written notebook.
        val temp = Files.createTempFile(path.parent, ".${path.name}", ".tmp")
        try {
            temp.writeText(text)
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.deleteIfExists()
        }
    }

    companion object {
        const val EXTENSION = ".kot"
        private val SEGMENT = Regex("""[\p{L}\p{N}_\- .()]+""")
        private val WINDOWS_DEVICE_NAMES =
            setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

        /** Validates [name] and returns its canonical form, e.g. ` abc.kot ` becomes `abc`. */
        fun normalizeName(name: String): String {
            val trimmed = name.trim().removeSuffix(EXTENSION).trim('/')
            if (trimmed.isEmpty()) throw InvalidNotebookNameException("Notebook name must not be empty")
            val segments = trimmed.split('/')
            for (segment in segments) {
                val reserved = segment.substringBefore('.').uppercase() in WINDOWS_DEVICE_NAMES
                if (!SEGMENT.matches(segment) || segment.startsWith('.') || segment != segment.trim() || reserved) {
                    throw InvalidNotebookNameException(
                        "Invalid notebook name '$name': use letters, digits, spaces, '_', '-', '.', '(' and ')', " +
                            "and '/' between folders",
                    )
                }
            }
            return segments.joinToString("/")
        }
    }
}
