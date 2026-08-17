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
    ./gradlew test          # 97 tests — the gate (JUnit5 unit + a JUnit3/4 BasePlatformTestCase + node/python hook-script tests)
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
- `storage/HeviewPaths.kt` — resolves `~/.heview` via `user.home`; `consumedDir` = `comments/consumed` (nested
  so the pool's `*.json` glob skips it), where a hook claims a consumed comment.
- `storage/CommentStore.kt` — in-memory index + JSON persistence; registered as an **application** service
  (the pool is shared). EDT-confined; disk I/O offloaded to a serial background executor (`runIo`, injectable).
  `hydrate()` loads the pool from disk once (read on `runIo`, apply on `runEdt` — both injectable → sync in
  tests); `forAbsPath(path)` returns a file's comments (normalized-path match); `addChangeListener` returns a
  `Disposable` to unregister. `markProcessed(uuid)` (→ Seen) / `evict(uuid)` (peer/user delete) mutate the
  index **in memory only, no disk write** — for the watcher; both idempotent.
- `ui/InlayCardHost.kt` — THE seam isolating the experimental inlay API (the ONE swap point).
- `ui/ComponentInlayCardHost.kt` — backs it via `Editor.addComponentInlay(offset, InlayProperties().relatesToPrecedingText(true), card, FIT_VIEWPORT_WIDTH)`, wrapped in `EditorScrollingPositionKeeper`. Verified present on 2024.2.
- `ui/CommentThread.kt` — the inlay card. Two entry points: `startCompose()` (an `EditorTextField`, so it owns
  editor keys) for the create flow, and `startDisplay(comment)` for an already-persisted comment. Renders a
  status chip (orange **Pending** / green **Seen**); `refreshDisplay(comment)` relabels a shown card in place
  (no-op while composing/unchanged). EDT-only; `onDispose` parented to the inlay; `dispose()` is idempotent.
- `ui/CommentInlayManager.kt` — **project** light `@Service`; the single controller that owns every
  `CommentThread`. Renders/disposes display cards per `Editor` on open/split/reopen/close (`EditorFactoryListener`),
  and reconciles all open editors on any `CommentStore` change so a comment appears/disappears in every split.
  Owns the create flow too (`compose(editor)`) — tracks the thread under its uuid *before* `store.save` fires, so
  reconcile never double-renders the composing editor. `init()` is EDT-only; boot it via the startup activity.
- `actions/AddCommentAction.kt` — "heview: Add Comment" (editor context menu / Ctrl+Alt+Shift+H); local files only;
  thin trigger that delegates to `CommentInlayManager.compose(editor)`.
- `hooks/HookInstaller.kt` — Phase 2: **atomically** extracts the bundled agent hook scripts into
  `~/.heview/<agent>/hooks/` (tmp→chmod→rename), detects an agent CLI via the **login-shell** PATH
  (`EnvironmentUtil.getValue("PATH")`, not the GUI launch PATH), and registers the `UserPromptSubmit` hook.
  Claude → idempotent edit of `~/.claude/settings.json` (dedup by injector filename; command shell-quoted).
  Codex → `~/.codex/hooks.json` entry + `config.toml`: hooks are default-on so it **never edits a present
  config.toml** (create-if-absent + warn-on-explicit-`false` only). SAFETY: a present-but-unparseable /
  non-object / wrong-type / non-UTF-8 config is left UNTOUCHED (never clobbered); writes are atomic and
  follow a symlink to its real target. `installAll()` returns a success Boolean (per-agent isolated) so the
  startup once-guard can re-arm and retry. All paths/`PATH`/warn injectable → unit-tested against temp dirs.
  Bundled injector scripts (`src/main/resources/hook-scripts/{claude-code,codex}/`) match by directory
  boundary + normalize paths, are UTF-8 + null-tolerant, and consume by an atomic **CLAIM** — move-then-emit
  into `comments/consumed/` (single-use; two agents can't double-inject), NOT `unlink` — the signal the watcher
  reads; the moved tombstone is then rewritten to `status:"processed"` (JS/Python byte-identical) so it reads back as Seen.
- `watch/CommentsPoolWatcher.kt` — Phase 3 application `@Service` (`Disposable`): a daemon NIO WatchService on
  `comments/`. On a `<uuid>.json` `ENTRY_DELETE`, a matching file in `consumed/` means an agent hook consumed
  it → `CommentStore.markProcessed` (Seen); a bare vanish → `evict` (peer/user delete). The tombstone is the
  consumed comment itself and is **left in place** (a slower live client, e.g. heview-vscode, must observe it;
  it's also the restore source) — reclaimed by an **age-based** startup `sweepConsumed()` (drop >14 days), never
  an eager per-client delete. Store calls hop to the EDT. The `consumed/` move replaces reviewa's suppression
  set (idempotent evict absorbs our own `delete`). Classification + FS effects are unit-tested directly (temp
  dirs, sync dispatch); the live WatchService thread is dogfood-only.
- `HeviewStartupActivity.kt` — `ProjectActivity`; dispatches `CommentInlayManager.init()` to the EDT, and on an
  **application-pooled** thread (off-EDT) starts the `CommentsPoolWatcher` (idempotent) and runs
  `HookInstaller.installAll()` **once per app** — both **skipped in unit-test/headless mode** so tests never
  spin a real watch thread or touch the real `~/.claude` / `~/.codex`.
`src/main/resources/META-INF/plugin.xml` registers the postStartupActivity, the CommentStore application service,
the action, and the `heview` notificationGroup (hook warnings). `CommentInlayManager` is a light `@Service` —
intentionally NOT in plugin.xml.
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
- Comments are **durable across sessions** (`hydrate()` on startup; no deactivation/project-close purge) —
  reviewa is session-scoped. Recreated inlays follow a live document `RangeMarker`, not the stored line.
- Hooks (Phase 2), sanctioned divergences from reviewa — all in plan.html §5/§6 + the parity allowlist:
  (1) injectors emit **clean backticks** in BOTH scripts (reviewa's Codex `hook.py` has a `\`` escaping
  bug); (2) cwd match is **directory-boundary** (`== cwd` or `startsWith(cwd+sep)`), not reviewa's raw
  `startsWith` which steals sibling-repo comments; (3) a **present-but-unparseable agent config is left
  untouched**, not overwritten fresh; (4) Codex flag is canonical `[features] hooks = true` (`codex_hooks`
  deprecated) and heview **never edits an existing `config.toml`** (hooks default-on → create-if-absent +
  warn-on-`false` only). These three (backtick, sibling-prefix, config-wipe) are latent bugs in reviewa too.
- **Built (Phase 3 consumption slice + shared tombstone contract):** injectors CLAIM a consumed comment by an
  atomic move into `~/.heview/comments/consumed/` (move-then-emit ⇒ single-use) rather than `unlink`;
  `CommentsPoolWatcher` marks a thread *Seen* on that signal vs `evict`s on a bare vanish — the `consumed/`
  move replaces reviewa's suppression set. `markProcessed`/`evict` never persist (a processed comment isn't
  written; its file already left the pool). **Four-rule contract (shared with heview-vscode): consume = atomic
  move; Seen = tombstone present for an in-index uuid; delete = vanished with no tombstone; retention =
  age-based sweep (>14 days), NEVER an eager per-client delete** (that would starve a slower peer). The
  tombstone IS the consumed comment (kept for a future UI restore). Still Later: CREATE/MODIFY sync so a
  comment created/edited in one already-open client appears in another without a restart.
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
Phase 0 (scaffold) + Phase 1 foundation + the **`CommentInlayManager`** increment + **Phase 2 — agent
hooks** + **Phase 3 — consumption watcher (consumed-dir slice)** are DONE. Phases 1–2 were each dogfooded
and taken through `/aeview-loop` to convergence; the end-to-end loop is **proven live** (a comment left in
the IDE is injected into Claude Code / Codex on `UserPromptSubmit` in the exact plan-§6 block and its
`<uuid>.json` is consumed). Gate green: **97 tests** (JUnit5 unit + a JUnit3/4 `BasePlatformTestCase` +
node/python behavioral hook-script tests), `buildPlugin` clean.

**Published**: https://github.com/MarlzRana/heview-intellij (public); `origin` is SSH, `main` tracks it.
The maintainer normally runs pushes — only push when asked. **The Phase-3 commits (`e2a8b64`..`04e29c7`)
are LOCAL / unpushed.**

Phase 3 just shipped (see `implementation_log.local.md` "Phase 3" + plan §4/§5/§8/§9): injectors CLAIM a
consumed comment by an atomic move into `comments/consumed/` (single-use); `CommentsPoolWatcher` flips a
thread *Seen* on that signal vs `evict`s on a bare vanish; `CommentThread` shows a Pending/Seen chip and
relabels in place. **Immediate next actions for this increment: (1) run `/aeview-loop`
(`range:8a69734..HEAD`) — Phase 3 touches concurrency + a background thread + FS races, so expect real
findings; (2) hand to the maintainer to dogfood in `runIde`.** THEN pick the next increment: remaining
Phase 3 (comment tool window, status-bar pending-count widget, copy actions, settings page incl. Seen
auto-collapse, scoped project-close cleanup) OR the Phase 1 reply/edit/re-pend→processed state machine.
Full deferred backlog (durable-anchor line-number writeback, external-reload listener, `CommentJson.decode`
validation, multi-client CREATE/MODIFY sync, GitHub identity, …) is in `implementation_log.local.md`.
</status>
