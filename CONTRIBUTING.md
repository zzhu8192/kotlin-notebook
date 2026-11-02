# Contributing to Kotlin Notebook

Thank you for your interest in contributing to Kotlin Notebook! We welcome bug reports, feature requests, documentation improvements, and code contributions.

## Code of Conduct

Please be respectful, constructive, and collaborative in all project interactions.

## Prerequisites

- **Java Development Kit (JDK)**: JDK 21 LTS or newer. Verify with `java -version`.
- **Node.js**: Node 18+ (only needed if developing or testing frontend assets in `frontend/`).
- **Gradle**: The project includes the `./gradlew` wrapper (Gradle 9.8.2).

## Getting Started

1. **Fork the Repository**: Create your own fork of [kotlin-notebook](https://github.com/zzhu8192/kotlin-notebook) on GitHub.
2. **Clone your fork**:
   ```bash
   git clone git@github.com:<your-username>/kotlin-notebook.git
   cd kotlin-notebook
   ```
3. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## Development Workflow

### Building and Testing

Run the full automated test suite (including REPL compiler, session management, and server tests):

```bash
./gradlew test
```

Build the distribution binaries:

```bash
./gradlew installDist
```

Build the fat jar:

```bash
./gradlew shadowJar
```

### Frontend Development

The frontend bundle is built using CodeMirror 6 and Marked. If you modify anything under `frontend/`:

```bash
cd frontend
npm install
npm run build     # compiles assets into src/main/resources/static/vendor/
npm run e2e       # runs end-to-end browser tests
```

### Coding Standards & Commit Guidelines

- Follow standard Kotlin idioms and formatting (4-space indents for Kotlin).
- Use [Conventional Commits](https://www.conventionalcommits.org/) for git messages:
  - `feat: ...` for new features
  - `fix: ...` for bug fixes
  - `docs: ...` for documentation changes
  - `chore: ...` for build, dependencies, or maintenance
- Ensure all tests pass (`./gradlew test`) before submitting a pull request.

## Submitting Pull Requests

1. Commit your changes with descriptive Conventional Commit messages.
2. Push your feature branch to your fork on GitHub.
3. Open a Pull Request against the `main` branch of this repository.
4. Include a concise description of what was changed and any related issue references.

## Reporting Bugs & Suggesting Enhancements

If you encounter a bug or have a suggestion:
- Search existing issues to see if it has already been reported.
- Open a new issue with:
  - Operating system and architecture
  - JDK version (`java -version`)
  - Clear steps to reproduce
  - Stack traces or error logs if applicable
