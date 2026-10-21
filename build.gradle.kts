plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "kotnb"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlin.scripting.ide.services)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.compiler.embeddable)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.scripting.dependencies)
    implementation(libs.kotlin.scripting.dependencies.maven)
    // Cells use kotlin-reflect, so match the compiler rather than whatever version a library brings along.
    implementation(kotlin("reflect"))
    implementation(libs.jline)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.content.negotiation)
}

application {
    mainClass = "kotnb.MainKt"
    // JLine loads a native library to drive the terminal.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// ---- Libraries that notebook cells compile against ----
//
// Cells see these without `@file:DependsOn`, plus the notebook's display API; the rest of the server's classpath
// (ktor, the compiler, ...) stays hidden from them. The server finds them through a generated index of jar names.

val scriptLibraryModules = setOf(
    "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime", "kotlinx-coroutines-core-jvm",
    "kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm", "kotlin-scripting-dependencies", "annotations",
)

val scriptLibraries = configurations.runtimeClasspath.map { classpath ->
    classpath.incoming.artifacts.artifacts
        .filter { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.module in scriptLibraryModules }
        .map { it.file }
}

val scriptClasspathIndex = tasks.register("scriptClasspathIndex") {
    val output = layout.buildDirectory.dir("generated/script-classpath")
    val names = scriptLibraries.map { files -> files.map { it.name }.sorted() }
    inputs.property("names", names)
    outputs.dir(output)
    doLast {
        val index = output.get().file("kotnb/script-classpath.txt").asFile
        index.parentFile.mkdirs()
        index.writeText(names.get().joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(scriptClasspathIndex)
}

/** Just the display API, so cells do not see the server's own classes. Shipped as a resource in every build. */
val displayApiJar = tasks.register<Jar>("displayApiJar") {
    archiveClassifier = "display-api"
    from(sourceSets.main.map { it.output.classesDirs }) {
        include("kotnb/display/**")
        include("META-INF/*.kotlin_module") // Lets the compiler find top-level functions such as html().
    }
}

tasks.shadowJar {
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles() // The compiler and ktor find their plugins through service files.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes("Main-Class" to application.mainClass.get(), "Enable-Native-Access" to "ALL-UNNAMED")
    }
    // Merged classes cannot serve as a compile classpath, so embed the original jars; the server extracts them.
    from(scriptLibraries) { into("kotnb/script-classpath") }
}

tasks.processResources {
    from(displayApiJar) {
        into("kotnb/script-classpath")
        rename { "display-api.jar" }
    }
}

tasks.startScripts {
    // Listing 120+ jars overflows cmd.exe's 8191 character line limit, so point both scripts at lib/* instead;
    // the java launcher expands the wildcard.
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace(Regex("(?m)^set CLASSPATH=.*$"), Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\*")),
        )
        unixScript.writeText(
            unixScript.readText().replace(Regex("(?m)^CLASSPATH=.*$"), Regex.escapeReplacement("CLASSPATH=\$APP_HOME/lib/*")),
        )
    }
}

tasks.named<JavaExec>("run") {
    // Notebooks live relative to where the server is started.
    workingDir = rootDir
    // Lets `./gradlew run` drive the REPL, though without line editing since Gradle's console is not a terminal.
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
