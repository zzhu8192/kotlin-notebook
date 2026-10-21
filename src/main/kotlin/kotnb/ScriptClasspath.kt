package kotnb

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContext

/**
 * What cells compile against: the libraries listed in the build's `kotnb/script-classpath.txt` and the notebook's
 * display API, but not the server's own classes and dependencies, which would clutter completion and clash with
 * libraries cells depend on.
 *
 * The display API ships as an embedded jar of its own. In a normal installation the libraries are on the server's
 * classpath. In the fat jar everything is merged into one file, which cannot be a compile classpath without exposing
 * the whole server, so the original library jars are embedded too. Embedded jars are extracted to a cache.
 */
internal object ScriptClasspath {
    private const val RESOURCE_DIRECTORY = "kotnb/script-classpath"
    private const val DISPLAY_API_JAR = "display-api.jar"

    val files: List<File> by lazy {
        val names = resource("$RESOURCE_DIRECTORY.txt")?.let { String(it).lines().filter(String::isNotBlank) }
            ?: error("Missing $RESOURCE_DIRECTORY.txt; build the server with Gradle")
        val embedded = resourceExists("$RESOURCE_DIRECTORY/${names.first()}")
        val libraries = if (embedded) names.map(::extract) else librariesFromClasspath(names)
        libraries + extract(DISPLAY_API_JAR)
    }

    private fun librariesFromClasspath(names: List<String>): List<File> {
        val classpath = scriptCompilationClasspathFromContext(
            classLoader = ScriptClasspath::class.java.classLoader,
            wholeClasspath = true,
        )
        val libraries = classpath.filter { it.name in names }.distinctBy { it.name }
        val missing = names - libraries.map { it.name }.toSet()
        check(missing.isEmpty()) { "Libraries for cells are missing from the classpath: $missing" }
        return libraries
    }

    /**
     * Copies an embedded jar to the temporary directory. The file name includes a hash of the content, so different
     * versions never share a file, and an existing copy can be reused without checking it.
     */
    private fun extract(name: String): File {
        val bytes = resource("$RESOURCE_DIRECTORY/$name") ?: error("Missing embedded library $name")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "kotlin-notebook", "script-classpath").createDirectories()
        val target = directory.resolve("$hash-$name")
        if (!target.exists()) {
            // Write next to the target and move it into place, so a concurrent start never reads a partial jar.
            val temp = Files.createTempFile(directory, name, ".tmp")
            Files.write(temp, bytes)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileAlreadyExistsException) {
                Files.deleteIfExists(temp)
            } catch (e: AccessDeniedException) {
                Files.deleteIfExists(temp) // Windows: another process has the finished file open.
            }
        }
        return target.toFile()
    }

    private fun resourceExists(path: String) = ScriptClasspath::class.java.classLoader.getResource(path) != null

    private fun resource(path: String): ByteArray? =
        ScriptClasspath::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
