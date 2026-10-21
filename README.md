# Kotlin Notebook

A small Zeppelin-style notebook and command line REPL for Kotlin.
Both run code in process with the Kotlin 2.4.21 compiler; a ktor server hosts the notebook UI.

## Running

Requires JDK 21.
Build a distribution once:

```sh
./gradlew installDist
```

Then start the REPL with no arguments, or the notebook server with `serve`:

```sh
build/install/kotlin-notebook/bin/kotlin-notebook                  # interactive REPL
build/install/kotlin-notebook/bin/kotlin-notebook serve            # http://localhost:8080
build/install/kotlin-notebook/bin/kotlin-notebook serve --port 9000 --notebooks ./notebooks
```

For a single self-contained file, build the fat jar (also built by `./gradlew build`):

```sh
./gradlew shadowJar
java -jar build/libs/kotlin-notebook-0.1.0-all.jar           # REPL
java -jar build/libs/kotlin-notebook-0.1.0-all.jar serve     # notebook
```

The fat jar extracts the few libraries cells compile against (about 8 MB) to `<temp>/kotlin-notebook/script-classpath` on first use.

`./gradlew run --args=serve` also starts the server.
`./gradlew run` starts the REPL, but without line editing, because Gradle's console is not a terminal.

## Command line REPL

The REPL behaves like the Scala REPL:

```
Welcome to Kotlin 2.4.21 (Java 21.0.12.1+1-LTS, OpenJDK 64-Bit Server VM).
Type in expressions for evaluation. Or try :help.

kotlin> fun twice(n: Int) =
      |     n * 2
kotlin> twice(21)
res2: Int = 42
kotlin> res2 + missing
error: Unresolved reference 'missing'.
  res2 + missing
         ^^^^^^^
```

- Input continues on the next line while it is incomplete, e.g. after an open brace or a trailing operator.
  Press Enter on two empty lines to submit incomplete input anyway and see its errors.
- Results are stored in `resN` variables that later input can use.
- Tab completes; input is highlighted with the Kotlin compiler's lexer.
- Ctrl+C interrupts a running snippet or discards the current input, and Ctrl+D exits.
- Commands: `:help`, `:load <file.kts or notebook.kot>`, `:reset` and `:quit`.
- History is kept in `~/.kotlin_notebook_history`.
- Piped input works too, e.g. `echo 'listOf(1, 2).sum()' | kotlin-notebook`.

## Notebook

Notebooks are stored in `./notebooks` relative to the working directory, e.g. `notebooks/abc.kot`.
Names may contain `/` to use folders.

The server listens on `127.0.0.1` only, because cells run arbitrary code with your permissions.
It also rejects requests from other origins, so web pages you visit cannot drive it.
Pass `--host 0.0.0.0` only on a network you trust.

## Using the notebook

| Key | Action |
| --- | --- |
| Shift+Enter | Run the cell and move to the next one |
| Ctrl+Enter | Run the cell |
| Alt+Enter | Run the cell and insert one below |
| Ctrl+Space | Show completions (they also appear while typing) |
| Tab / Enter | Accept a completion |
| Up / Down at the first or last line | Move to the previous or next cell |
| Ctrl+S | Save now (edits are also saved automatically) |

- Cells share one Kotlin session per notebook, so declarations carry over between cells.
- **Interrupt** cancels queued cells and interrupts the running one.
- **Restart** starts a fresh session.
- A failing cell cancels the cells queued after it.
- Markdown cells render when run; double-click one to edit it.
- Return `html("...")` from a cell to render HTML instead of text.
- Use Maven libraries with `@file:DependsOn("group:artifact:version")` and add repositories with `@file:Repository("https://...")`.
- Cells compile against the Kotlin standard library, kotlin-reflect, kotlinx-coroutines and kotlinx-serialization.
  Anything else, including the server's own libraries, needs `@file:DependsOn`.
  The list lives in `scriptLibraryModules` in `build.gradle.kts`.
- The editor highlights Kotlin like IntelliJ IDEA, using IntelliJ Light colors in the light theme and Darcula in the dark theme.

## File format

`.kot` files follow the Databricks notebook source format for Scala, with a Kotlin header:

```kotlin
// Kotlin notebook source
val x = 1

// COMMAND ----------

// MAGIC %md
// MAGIC # A markdown cell
```

- Kotlin cells are stored verbatim.
- Markdown cells are `// MAGIC` lines starting with `%md`.
- A Kotlin cell that would be ambiguous is stored as a `// MAGIC %kotlin` cell instead.
  That applies when it contains a `// COMMAND ----------` line, or when every line starts with `// MAGIC`.
- Outputs are not saved, so files stay clean in version control.
- Leading and trailing blank lines of a cell are not preserved.
- Files exported from Databricks (`// Databricks notebook source`) open as well; cells in other languages are kept as text.

## How it works

- `KotlinRepl` runs cells with the K2 REPL compiler (`K2ReplCompiler` and `K2ReplEvaluator`), so cells get the full Kotlin 2.4 language.
- Completion uses `KJvmReplCompilerWithIdeServices`, the only REPL completion engine Kotlin ships, which is based on the K1 frontend.
  Every cell that runs is also compiled, but not run, by this completer in the background, so completion follows the same declarations and shadowing as execution.
  Cells K1 cannot compile, such as ones using K2-only syntax, are added as text before the code being completed instead.
- `NotebookSession` gives each notebook a kernel thread and a run queue.
  Browsers receive output, results and status over a WebSocket (`/ws?name=...`).
- `OutputRouter` captures `System.out` and `System.err` per cell, including from threads the cell starts.
- `Cli` is the command line REPL, built on JLine, the line editor the Scala REPL uses.
  It uses the same `KotlinRepl`, and the compiler's parser decides whether input is complete.
- The UI is plain JavaScript in `src/main/resources/static`.
  CodeMirror 6 and marked are bundled into `static/vendor/vendor.js`, so the app works offline.

To use a newer Kotlin version, change `kotlin` in `gradle/libs.versions.toml`.
The test suite also passes with Kotlin 2.5.0-Beta1.

## Development

```sh
./gradlew test                     # unit, server and REPL tests
./gradlew installDist
cd frontend && npm install && npm run e2e   # drives the UI in Chrome
KOTNB_JAR=../build/libs/kotlin-notebook-0.1.0-all.jar npm run e2e   # same, against the fat jar
```

After changing frontend dependencies, rebuild the vendored bundle with `npm run build` in `frontend`.
Set `KOTNB_BROWSER=msedge` to run the end-to-end test in Edge instead of Chrome.

## Limitations

- Interrupting relies on `Thread.interrupt()`.
  A cell busy in a loop that never checks for interrupts keeps running; restarting the kernel abandons its thread, but the thread keeps using CPU until the server stops.
- Starting a kernel takes a few seconds, and the first completion after that a few more, while the compilers initialize.
- Kotlin 2.5 deprecates the K1 completion engine without a replacement yet.
  It still works there, but a future Kotlin release may remove it.
