# heview (IntelliJ)

JetBrains-IDE port of **reviewa** / **heview**: leave inline code-review comments on any
file, automatically injected into Claude Code and Codex via hooks to be resolved.

See [`plan.html`](./plan.html) for the full design, phasing, and on-disk contract.

## Requirements

- **JDK 21** for building (the machine's default JDK may be newer; the build pins to 21).
- Gradle is provided via the wrapper (`./gradlew`, pinned to 8.10.2).

## Develop

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

./gradlew runIde         # launch a sandbox IDE with heview loaded
./gradlew buildPlugin    # build the installable zip → build/distributions/
./gradlew test           # unit tests
./gradlew verifyPlugin   # JetBrains Plugin Verifier across target IDEs
```

To dogfood in your daily IDE: `./gradlew buildPlugin`, then
**Settings → Plugins → ⚙ → Install Plugin from Disk** and pick the zip.
