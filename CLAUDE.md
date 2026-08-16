<overview>
heview-intellij is a JetBrains-IDE plugin porting **reviewa-vscode** (being renamed "heview"): leave
inline code-review comments on files, written to a shared on-disk pool and injected into Claude Code /
Codex via hooks to be resolved. `plan.html` (repo root) is the authoritative design + phasing artifact
and the primary thing the maintainer judges by — keep it current. `implementation_log.local.md`
(gitignored) is the running build log: what shipped per increment, decisions, and the live backlog.
Reference source being ported: `~/gh/MarlzRana/reviewa-vscode`. IntelliJ SDK docs: `~/gh/JetBrains/intellij-sdk-docs`.
</overview>

<build>
CRITICAL: builds run on **JDK 21**, but the machine default `java` is JDK 26 (too new for this stack).
Export JAVA_HOME before every Gradle command:

    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

- Use the **wrapper only**, pinned to Gradle 8.10.2 (`./gradlew`, or `./gradlew -p <repo>`). Do NOT use
  the machine's brew gradle (9.x) for builds — it was used once only to bootstrap the wrapper.
- Commands (run from the repo root):
    ./gradlew test          # 43 tests — the gate (JUnit5 unit tests + a JUnit3/4 BasePlatformTestCase via the vintage engine)
    ./gradlew buildPlugin    # → build/distributions/heview-*.zip
    ./gradlew runIde         # sandbox IDE (GUI; the MAINTAINER runs this to dogfood — don't launch it headless)
    ./gradlew verifyPlugin   # JetBrains Plugin Verifier
- `instrumentCode` and `buildSearchableOptions` are disabled in build.gradle.kts (pure-Kotlin plugin;
  also sidesteps an instrumentCode JDK-compiler-path failure). Don't re-enable without a reason.
- Gson is bundled (`implementation`, errorprone-annotations excluded) — the platform doesn't expose it.
- Prefer explicit `git add <paths>`; IDE-generated `bin/` is gitignored but `git add -A` still risks noise.
- To verify a platform API/signature empirically, `javap` the classes in the resolved IDE jars under
  `~/.gradle/caches/**/ideaIC-2024.2.1*/lib/` (and `lib/modules/`). This is how the inlay API was pinned.
</build>

<stack>
Kotlin 1.9.25 + IntelliJ Platform Gradle Plugin 2.1.0. Target IC 2024.2.1; `sinceBuild=242`, no
`untilBuild` (all current & future JetBrains IDEs). Plugin id `com.marlzrana.heview`.
</stack>

<architecture>
Source of truth for a comment = its file in the shared pool `~/.heview/comments/<uuid>.json`; the JSON
schema is byte-compatible with reviewa's so the coding-agent hooks read it. Code map (`src/main/kotlin/com/marlzrana/heview/`):
- `model/HeviewComment.kt` — schema data class (`@SerializedName` snake_case) + `CommentSide` / `CommentStatus` / `IntendedConsumer` enums.
- `model/CommentJson.kt` — Gson encode/decode (`disableHtmlEscaping`; nulls omitted).
- `model/NewComment.kt` — `newFileComment(...)`: pure, testable v1 comment factory (1-based line, side=FILE, logical==abs).
- `util/HeviewTime.kt` — `nowIso()`: fixed 3-digit-millis ASCII ISO (matches JS `toISOString` so ordering matches reviewa).
- `storage/HeviewPaths.kt` — resolves `~/.heview` via `user.home`.
- `storage/CommentStore.kt` — in-memory index + JSON persistence; registered as an **application** service
  (the pool is shared). EDT-confined; disk I/O offloaded to a serial background executor (`runIo`, injectable).
  `hydrate()` loads the pool from disk once (read on `runIo`, apply on `runEdt` — both injectable → sync in
  tests); `forAbsPath(path)` returns a file's comments (normalized-path match); `addChangeListener` returns a
  `Disposable` to unregister.
- `ui/InlayCardHost.kt` — THE seam isolating the experimental inlay API (the ONE swap point).
- `ui/ComponentInlayCardHost.kt` — backs it via `Editor.addComponentInlay(offset, InlayProperties().relatesToPrecedingText(true), card, FIT_VIEWPORT_WIDTH)`, wrapped in `EditorScrollingPositionKeeper`. Verified present on 2024.2.
- `ui/CommentThread.kt` — the inlay card. Two entry points: `startCompose()` (an `EditorTextField`, so it owns
  editor keys) for the create flow, and `startDisplay(comment)` for an already-persisted comment. EDT-only;
  `onDispose` parented to the inlay; `dispose()` is idempotent.
- `ui/CommentInlayManager.kt` — **project** light `@Service`; the single controller that owns every
  `CommentThread`. Renders/disposes display cards per `Editor` on open/split/reopen/close (`EditorFactoryListener`),
  and reconciles all open editors on any `CommentStore` change so a comment appears/disappears in every split.
  Owns the create flow too (`compose(editor)`) — tracks the thread under its uuid *before* `store.save` fires, so
  reconcile never double-renders the composing editor. `init()` is EDT-only; boot it via the startup activity.
- `actions/AddCommentAction.kt` — "heview: Add Comment" (editor context menu / Ctrl+Alt+Shift+H); local files only;
  thin trigger that delegates to `CommentInlayManager.compose(editor)`.
- `HeviewStartupActivity.kt` — `ProjectActivity`; dispatches `CommentInlayManager.init()` to the EDT (the activity
  runs on a background coroutine).
`src/main/resources/META-INF/plugin.xml` registers the postStartupActivity, the CommentStore application service,
and the action. `CommentInlayManager` is a light `@Service` — intentionally NOT in plugin.xml.
</architecture>

<conventions>
- Keep the experimental inlay call ONLY inside `ComponentInlayCardHost`; everything else goes through `InlayCardHost`.
- Tests: JUnit5 (`tasks.test { useJUnitPlatform() }`) for pure logic — tested against a `@TempDir` + an
  injected synchronous executor: `CommentStore(dir, runIo = { it.run() }, runEdt = { it.run() })`. Shared
  factory `sampleComment(...)` in `src/test/.../Factories.kt`.
- UI-lifecycle tests use a **JUnit3/4 `BasePlatformTestCase`** (`ui/CommentInlayManagerTest.kt`), run under
  the same `useJUnitPlatform()` via the **junit-vintage** engine; the platform test framework comes from
  `intellijPlatform { testFramework(TestFrameworkType.Platform) }` + `junit:junit` on the test classpath.
  Pattern: construct a dedicated `CommentInlayManager(project)` (NOT the service singleton — the real
  startup activity drives that), inject a temp-dir store via `manager.storeOverride` and a fake
  `manager.hostFactory` (records live-card counts, avoids the experimental inlay API), and drive real
  local-file editors via `EditorFactory.createEditor(...)`. Whitelist the temp dir with
  `VfsRootAccess.allowRootAccess(testRootDisposable, tempDir.toRealPath().toString())` (macOS /var→/private/var).
- Every change adds/updates tests; `./gradlew test` must be green before considering a change done.
- Commits: conventional prefixes (feat/fix/refactor/nit), imperative; use the `commit` skill's message
  guidance; end EVERY message with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Commit
  directly to `main`, incrementally ("commit as you go"). The maintainer runs pushes — don't push unless asked.
</conventions>

<settled-decisions>
Do NOT re-litigate these (recorded in plan.html + the parity reviewer's do-not-flag allowlist). A
context-blind reviewer will keep raising them:
- Persistence is async/off-EDT + atomic tmp→rename (the platform forbids blocking I/O on the EDT); the
  durability race isn't practically reachable; a persist failure is logged, not surfaced.
- `delete` = optimistic remove + fire, then best-effort background unlink (reviewa parity).
- `CommentStore` is EDT-confined by convention (no locking); the injectable sync executor is the test seam.
- v1 field simplifications: `side` always "file"; `logical_abs_path == abs_path`; `workspace` = project
  basePath; `line_content` from the editor buffer at submit; blank/whitespace submits dropped; author is
  the OS user (`user.name`). Gemini CLI support removed entirely. GitHub identity/avatar deferred.
</settled-decisions>

<review>
Multi-harness review via `aeview` (`~/gh/MarlzRana/aeview`, on PATH as `aeview`). Repo-local reviewers:
`.aeview/reviewers/{intellij-platform,hooks-integrity,parity,tests,simplify}/REVIEWER.md`
(auto-activate on `**/*.kt`, etc.). Harnesses per reviewer: codex `gpt-5.6-terra` (NOT `gpt-5.6-sol` —
unsupported on a ChatGPT-account codex), pi `xai/grok-4.6`, claude-code `claude-opus-4-8`.
Run from the repo root: `aeview run --scope range:<base>..HEAD --json`; loop skill `/aeview-loop`.
Re-invoke aeview PER INCREMENT (a fresh diff), NOT repeatedly on the same slice — the panel re-raises
deferred-but-planned increments every run, so extra cycles on one slice hit diminishing returns fast.
Always filter findings premised on unbuilt-but-planned features or already-settled decisions.
</review>

<status>
Phase 0 (scaffold) + Phase 1 foundation (schema, store, create/display/delete inlay UI) + the
**`CommentInlayManager`** increment (per-editor recreate/dispose on open/split/reopen/close + startup
`hydrate()` from `~/.heview/comments`) are DONE. Phase 1 foundation went through a full `/aeview-loop`
(5 cycles → converged); the CommentInlayManager increment is NOT yet aeview-reviewed or dogfooded in
`runIde`. Gate green: 32 tests, build clean.

Recommended next increment: **Phase 2 — hook install & registration** (Claude Code + Codex): ship the
hook scripts into `~/.heview/{claude-code,codex}/hooks/` and do idempotent config registration (see
plan.html §4/§6). This is the step that makes comments actually reach the agents — the first end-to-end
loop. Alternatives the maintainer may prefer first: the Phase 1 reply/edit/re-pend→processed state machine,
or the Phase 3 consumption watcher (marks a thread Seen when a hook deletes its file). The full backlog
(status-aware persistence, platform-fixture UI tests, `CommentJson.decode` validation, GitHub identity, …)
lives in `implementation_log.local.md`. NOTE: the display card header still hard-codes "Pending" — a
status-aware label is deferred with the state machine.
</status>
