<overview>
heview-intellij is a JetBrains-IDE plugin porting **reviewa-vscode** (being renamed "heview"): leave
inline code-review comments on files, written to a shared on-disk pool and injected into Claude Code /
Codex via hooks to be resolved. `plan.html` (repo root) is the authoritative design + phasing artifact
and the primary thing the maintainer judges by — keep it current. `implementation_log.local.md`
(gitignored) is the running build log: what shipped per increment, decisions, and the live backlog — its
top **RESUME HERE** banner is the fastest way back in. `changes_to_be_made_to_vscode_variant.local.md`
(gitignored) is the hand-off list of fixes + the shared `processed/` tombstone contract to port to the VS
Code variant — append to it when a new reviewa/heview-vscode divergence surfaces (don't edit reviewa itself).
Reference source being ported: `~/gh/MarlzRana/reviewa-vscode`. IntelliJ SDK docs: `~/gh/JetBrains/intellij-sdk-docs`.
</overview>

<build>
CRITICAL: builds run on **JDK 21**, but the machine default `java` is JDK 26 (too new for this stack).
Export JAVA_HOME before every Gradle command:

    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

- Use the **wrapper only**, pinned to Gradle 8.10.2 (`./gradlew`, or `./gradlew -p <repo>`). Do NOT use
  the machine's brew gradle (9.x) for builds — it was used once only to bootstrap the wrapper.
- Commands (run from the repo root):
    ./gradlew test          # 203 tests — the gate (JUnit5 unit + a JUnit3/4 BasePlatformTestCase + node/python hook-script tests)
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
- `model/HeviewComment.kt` — thread schema data class (`@SerializedName` snake_case) + `CommentSide` /
  `CommentStatus` / `IntendedConsumer` enums, plus `HeviewReply(content,status,author,createdAt,id)`. A thread
  holds `replies: List<HeviewReply>?` (heview superset; a foreign/legacy file omits it → one reply). Each reply
  has a stable `id` (edit/delete/re-pend match on it, surviving a concurrent status/content change; an id-less
  foreign reply is backfilled distinctly). Top-level `content` (PENDING replies joined by a blank line — the
  only field hooks read) and `status` are DERIVED.
- `model/CommentJson.kt` — Gson encode/decode (`disableHtmlEscaping`; nulls omitted).
- `model/NewComment.kt` — `newFileComment(...)`: pure, testable v1 comment factory (1-based line, side=FILE, logical==abs).
- `model/CommentEdits.kt` — pure helpers for the reply model: `recomputed(now)` re-derives the thread's
  `content`/`status`/`created_at` from its replies (PENDING join; PROCESSED iff none actionable);
  `normalizedReplies(fallbackAuthor)` reconstructs a single reply from `content` for a foreign/legacy file.
- `util/HeviewTime.kt` — `nowIso()`: fixed 3-digit-millis ASCII ISO (matches JS `toISOString` so ordering matches reviewa).
- `storage/HeviewPaths.kt` — resolves `~/.heview` via `user.home`; `processedDir` = `comments/processed` (nested
  so the pool's `*.json` glob skips it), where a hook claims a consumed comment.
- `storage/CommentStore.kt` — in-memory index + JSON persistence; registered as an **application** service
  (the pool is shared). EDT-confined; disk I/O offloaded to a serial background executor (`runIo`, injectable).
  `hydrate()` loads the pool from disk once (read on `runIo`, apply on `runEdt` — both injectable → sync in
  tests); `forAbsPath(path)` returns a file's comments (normalized-path match); `addChangeListener` returns a
  `Disposable` to unregister. `markProcessed(uuid)` (→ flips every reply Seen) / `evict(uuid)` (peer/user delete) mutate the
  index **in memory only, no disk write** — for the watcher; both idempotent. `updateLocation(uuid, line, lineContent)`
  rewrites **only** the durable anchor (`line_number`/`line_content`) — the save/reload writeback; no-op unless
  changed; updates the in-memory record for any known uuid (even a not-persisted Seen/in-flight thread, so a
  later re-pend uses the moved line). The disk write is **always queued** on the serial IO executor (never
  short-circuited on the EDT — so a write requested while the create-save is in flight lands *behind* it rather
  than being dropped), and the IO task **skips** unless the file is still in `comments/` (`isPersisted` +
  `Files.exists` recheck, so it never recreates a consumed thread — residual move-vs-write TOCTOU → deferred
  generation fence); reverts the memory update on a write failure so the next save retries; **fires no change
  listener** (the card already tracks the live marker). Per-reply state
  machine (plan §5): `addReply`/`editReply`/`rependReply`/`deleteReply` mutate the reply list, `recompute`
  the derived fields, and re-persist via a private `revive()` that **first removes the `processed/`
  tombstone** (a tombstone for an in-index uuid is the watcher's Seen signal — leaving it would let the next
  reconcile flip the thread straight back); deleting the last reply drops the thread, deleting the last
  PENDING reply of a mixed thread keeps the Seen replies but unlinks the pool file. `hydrate`/`decodeIfValid`
  **normalize replies** (drop malformed, synthesize one from `content` if none) via a `defaultAuthor`
  provider so every indexed thread has ≥1 well-formed reply. `whenHydrated(cb)` runs `cb`
  once after hydrate applies (a one-shot for the watcher's post-hydrate reconcile — not an every-change
  listener). `isPersisted(uuid)` (a `persisted` set: write-succeeded or hydrated, cleared on evict/delete/
  markProcessed) lets the watcher evict only a comment that was on disk, never one whose create-save failed.
- `ui/InlayCardHost.kt` — THE seam isolating the experimental inlay API (the ONE swap point).
- `ui/ComponentInlayCardHost.kt` — backs it via `Editor.addComponentInlay(offset, InlayProperties().relatesToPrecedingText(true), card, FIT_VIEWPORT_WIDTH)`, wrapped in `EditorScrollingPositionKeeper`. Verified present on 2024.2.
- `ui/CommentThread.kt` — the inlay card, rendering a **thread as a `VerticalLayout` stack of reply rows** +
  a "Leave a comment" box. `startCompose()` (an `EditorTextField`, owns editor keys) creates a new thread's
  first reply; `startDisplay(comment)` renders an existing thread. Each reply row shows author + a Pending/Seen
  chip and **icon buttons** (`AllIcons.Actions` GC delete / Edit pencil always; `AllIcons.Vcs.History` clock
  re-pend only on a Seen reply — reviewa's trash/pencil/clock UI); an inline per-row **edit** sub-mode swaps
  that row for an editable field (an `editingIndex` suppresses `refreshDisplay` so an in-progress edit
  survives an unrelated reconcile). Actions route to `onReply`/`onEditReply`/`onDeleteReply`/`onRependReply`
  (index-based, valid within one render); `refreshDisplay(comment)` rebuilds the stack in place (no-op while
  composing/editing/unchanged). EDT-only; `onDispose` parented to the inlay; `dispose()` is idempotent.
- `ui/CommentInlayManager.kt` — **project** light `@Service`; the single controller that owns every
  `CommentThread`. Renders/disposes display cards per `Editor` on open/split/reopen/close (`EditorFactoryListener`),
  and reconciles all open editors on any `CommentStore` change so a comment appears/disappears in every split.
  A third trigger — a `FileDocumentManagerListener` (app-level `TOPIC`, connection parented to the manager) —
  handles an **external file reload** (an agent editing a file to resolve a comment): `fileContentReloaded`
  can dispose the inlays but fires no store change, so the handler reconciles every open editor of the file
  (matched by `document ===`) to recreate the disposed cards. It **trusts the anchors** — IntelliJ's reload
  diffs the text (common-affix trimming in `DocumentImpl.replaceString`), so a surviving `RangeMarker` is
  correctly shifted and beats the persisted `line_number` an external edit never updated; only a marker the
  reload genuinely invalidated is rebuilt from `line_number` (by `currentLineEndOffset`). Crucially, the display
  card's `onDispose` does **not** retire the shared anchor (a transient reload-dispose keeps the valid marker so
  the immediately-recreated card reuses its shifted position; a deleted comment / closed editor still retire it
  via reconcile's removal branch / `forget`). Both `fileContentReloaded` and `beforeDocumentSaving` run the same
  **line_number writeback** (`writeBackAnchors`): for each valid anchor on the document, write the marker's
  current line/line_content back via `store.updateLocation` (a no-op unless it moved), so the durable
  `line_number` tracks the on-disk file the agent reads. Both are correct sync points — in each, the document
  equals disk (a save flushes it; a reload just loaded it). An *unsaved* in-IDE edit leaves the pool alone (the
  agent still reads the old on-disk file, so the stale-looking number is actually consistent with it).
  Owns the create flow too (`compose(editor)`) — tracks the thread under its uuid *before* `store.save` fires, so
  reconcile never double-renders the composing editor. A shared `newThread()` wires every card's per-reply
  callbacks (`onReply`/`onEditReply`/`onDeleteReply`/`onRependReply`) to the store (`created_at` via
  `HeviewTime.nowIso()`, new replies authored by the OS user). `init()` is EDT-only; boot it via the startup activity.
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
  boundary + normalize paths, are UTF-8 + null-tolerant, format each comment defensively (per-comment guard),
  and consume by an atomic **CLAIM** — move-then-emit into `comments/processed/` (single-use; two agents can't
  double-inject), NOT `unlink` — the signal the watcher reads. Then they stamp the tombstone's mtime to
  consumption time and rewrite it to `status:"processed"` (JS/Python byte-identical) via an **exclusive-create**
  temp so it reads back as Seen. Both refuse a **symlinked** `processed/` (never write outside heview).
- `watch/CommentsPoolWatcher.kt` — Phase 3 application `@Service` (`Disposable`): a daemon NIO WatchService on
  `comments/`. On a `<uuid>.json` `ENTRY_DELETE`, a matching file in `processed/` → `CommentStore.markProcessed`
  (Seen); a bare vanish → `evict` (peer/user delete); an atomic-replace (pool file still present) is ignored.
  A one-shot `reconcile()` (via `store.whenHydrated`, after each watch (re)registration, and on OVERFLOW)
  catches missed events: it marks Seen a consumed comment, and — only on OVERFLOW, gated by
  `store.isPersisted` snapshotted on the EDT — evicts a persisted-then-vanished comment (the register/rebuild
  path never evicts, so a rebuild that recreates the dir empty can't mass-evict). The tombstone is left in
  place (peers + restore) and reclaimed by an **age-based** `sweepProcessed()` (drop `*.json`/`*.json.tmp`
  >14 days; refuses a symlinked dir). The daemon **self-heals** (rebuilds the watch if the dir is
  removed/replaced, bounded retries) and drops queued EDT hops once disposed. Classification + FS effects are
  unit-tested directly; the live WatchService loop is dogfood-only.
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
  atomic move into `~/.heview/comments/processed/` (move-then-emit ⇒ single-use) rather than `unlink`;
  `CommentsPoolWatcher` marks a thread *Seen* on that signal vs `evict`s on a bare vanish — the `processed/`
  move replaces reviewa's suppression set. `markProcessed`/`evict` never persist (a processed comment isn't
  written; its file already left the pool). **Four-rule contract (shared with heview-vscode): consume = atomic
  move; Seen = tombstone present for an in-index uuid; delete = vanished with no tombstone; retention =
  age-based sweep (>14 days), NEVER an eager per-client delete** (that would starve a slower peer). The
  tombstone IS the consumed comment (kept for a future UI restore). Still Later: CREATE/MODIFY sync so a
  comment created/edited in one already-open client appears in another without a restart.
- **Phase-3 `/aeview-loop` outcomes (settled; don't re-litigate — the panel re-raises them):**
  (a) reconcile is a one-shot catch-up (`whenHydrated` + post-register + OVERFLOW), NOT an every-change
  listener; it marks Seen always, and **evicts a lost bare-delete only on OVERFLOW** (dir intact), gated by
  `isPersisted` — the register/rebuild path never evicts, so a rebuild that recreates the pool dir empty
  can't mass-evict; (b) injectors do an **atomic** tombstone rewrite (temp + exclusive-create + rename),
  **refuse a symlinked `processed/`**, and **stamp the tombstone mtime** to consumption time; (c) accepted
  tradeoff: **single-use over at-least-once delivery** — a hook killed between the claim (move) and the
  stdout write marks a comment Seen the agent never saw (emit-first would reopen double-inject); (d) the
  live `WatchService` loop is **dogfood-only** (the classification/effect logic is all unit-tested); (e)
  the read-to-claim "stale snapshot" race is a **non-issue in v1** (no edit path → content is immutable
  after create). Reviewer coverage note: some cycles had ~6/18 reviewers fail (harness/token infra), not
  findings.
- **Built (Phase 1 multi-reply threads + per-reply state machine — plan §5):** a thread (one uuid/file)
  holds an ordered `replies[]` (a heview **superset** field: `{content,status,author,created_at,id}` — the
  `id` is a stable per-reply identity so edit/delete/re-pend match the right reply through a concurrent change), each
  reply with its own status and its own edit/delete/re-pend actions — reviewa's per-comment UI, which reviewa
  keeps only in memory (it is session-scoped). heview **persists** it (durable). Top-level `content`
  (PENDING replies joined by `\n\n`) + `status` stay **DERIVED** (`recomputed`), so the injectors are
  **unchanged** (they read `content` + move the whole file on consume). **Two-state per reply** (maintainer
  decision): PENDING / PROCESSED — edit + delete always, re-pend only on Seen, editing a Seen reply revives
  it, consuming the thread flips **all** replies. `addReply`/`editReply`/`rependReply`/`deleteReply` recompute
  + re-persist; a revive **first clears the `processed/` tombstone** (else the next reconcile re-marks Seen);
  deleting the last reply drops the thread, the last PENDING reply of a mixed thread keeps the Seen replies
  but unlinks the pool file (reviewa parity). Hydrate **normalizes** a foreign/legacy single-`content` file
  into one reply. **Deferred (don't build without a decision):** restoring a fully-consumed thread from its
  `processed/` tombstone across a restart (in-session it stays visible + re-pendable); a thread-level delete
  button; per-reply multi-author; the injectors flipping `replies[]` statuses in the tombstone (only needed
  once restore-from-tombstone exists — harmless now).
- **Phase-1 `/aeview-loop` outcomes (settled; don't re-litigate — the panel re-raises them every run):**
  5 cycles to the cap (findings 17→19→16→15→15). Hardened: **stable per-reply `id`** for mutation targeting
  (survives a concurrent status/content flip); `refreshDisplay` tracks the latest thread while an edit is
  open; revive **writes first, clears the tombstone only on write success**; `deleteReply` removes exactly
  one + sanitize backfills null/duplicate ids; **double-click Delete guard** (icon starts disabled, enable-
  later); reply-box **draft carried across a rebuild**; `deleteTombstone` path-separator + symlink guards.
  **Open = deferred, NOT bugs to fix now:** (a) the **multi-client / generation race family** — a
  cross-process hook claim racing a store save/revive, and a one-shot `reconcile` racing a revive/retain —
  belongs to the deferred multi-client-sync + generation-fencing increment (v1 is single-client; windows are
  microsecond-narrow); (b) **visual pass** (scroll-preserve on rebuild, aggregate thread label, lazy reply
  box, focus/caret) — maintainer-acknowledged follow-up; (c) preferences (sealed `CardState`, pass `targetId`)
  — code is correct + tested.
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
hooks** + **Phase 3 — consumption watcher (processed-dir slice)** are DONE — each dogfooded and taken
through `/aeview-loop`. The end-to-end loop is **proven live** (a comment left in the IDE is injected into
Claude Code / Codex on `UserPromptSubmit` in the exact plan-§6 block, its `<uuid>.json` is claimed into
`processed/`, and the card flips Pending→Seen). Gate green: **203 tests** (JUnit5 unit + a JUnit3/4
`BasePlatformTestCase` + node/python behavioral hook-script tests), `buildPlugin` clean.

**Published + pushed**: https://github.com/MarlzRana/heview-intellij (public); `origin` is SSH, `main`
tracks it; `main` == `origin/main` == `41ad48f`. The maintainer normally runs pushes — only push when asked.

**Shipped + dogfooded + pushed — Phase 1 multi-reply comment threads + per-reply state machine (plan §5),
through a full 5-cycle `/aeview-loop`.** A thread holds an ordered `replies[]` (heview superset field:
`{content,status,author,created_at,id}` — the `id` is a stable per-reply identity), each reply with its own
Pending/Seen status and its own edit / delete / re-pend actions (reviewa's per-comment UI, persisted because
heview is durable). Top-level `content`/`status` stay derived so the injectors are unchanged. Two-state per
reply; the card renders a stack of reply rows (trash/pencil always, clock re-pend on Seen) + a "Leave a
comment" box, with Cmd/Ctrl+Enter-to-submit and focus landing in the reply box after submit. The
`/aeview-loop` ran to the 5-cycle cap (findings 17→19→16→15→15); everything it surfaced is fixed or a
recorded deferral — see `<settled-decisions>` ("Built (Phase 1 …)" + "Phase-1 `/aeview-loop` outcomes").
Gate: **203 tests**, `buildPlugin` clean.

**Shipped + dogfooded + `/aeview-loop`-hardened — external-file-reload handling** (former cycle-2 #4 gap). An
agent editing a file to resolve a comment triggers a document reload that can dispose the inlays and — unlike a
store change — fires no reconcile, so cards went blank until reopen. Fix in `ui/CommentInlayManager`: a
`FileDocumentManagerListener` (app-level `TOPIC`, connection parented to the manager) whose `fileContentReloaded`
reconciles every open editor of the file to recreate disposed cards. **The panel (2 cycles) corrected the
original design:** it now **trusts the reload-shifted anchors** rather than dropping them — IntelliJ's reload
diffs the text, so a surviving `RangeMarker` stays attached to its code and beats the stale `line_number`; only a
genuinely invalidated marker falls back to `line_number`. Cycle 2 caught that the card's `onDispose` was still
retiring the (valid) shared anchor on a reload-dispose, defeating this — fixed by not retiring there. And since
an external edit fires a *reload* not a *save*, `fileContentReloaded` **also runs the writeback** so the
injectable `line_number` isn't left frozen. Driven by a `@TestOnly simulateFileContentReloadedForTest` seam + a
real-message-bus wiring test. Dogfood confirmed (a source-file reload keeps the card).

**Shipped — durable-anchor line_number writeback** (LOCAL/unpushed; former cycle-3 #2 gap). The persisted
`line_number`/`line_content` were frozen at submit, so after edits above a comment the on-disk number went stale
— and the injector, a reopen, and the reload fallback all read it. Both `beforeDocumentSaving` and (post-panel)
`fileContentReloaded` run a shared `writeBackAnchors` → `store.updateLocation`. **Decision (AskUserQuestion):
save-gated, not edit-gated** — the agent reads the file from disk, so the pool matches it when the document lands
on disk (a save flushes it; a reload just loaded it); an unsaved in-IDE edit correctly leaves the pool alone
(supersedes the backlog's "coalesced document-change listener", which would desync the pool from the unsaved
file). `updateLocation` touches only `line_number`/`line_content`, no-ops unless changed, always queues its write
on the serial IO executor (so an in-flight-create write isn't dropped) which skips unless the file is still in
`comments/` (never resurrects a consumed thread), reverts on write failure, and fires no change listener.

Both increments are in a `/aeview-loop` (scope `f22ca42..HEAD`; findings 13→12→12→7, each cycle's fixes applied
above — cycle 3 fixed a regression from cycle 2 (a `RangeMarker`/`Document` leak on editor close) and moved the
writeback's Gson encode off the EDT; cycle 4 was test-gaps + a cleanup, its two HIGHs re-flags of the deferred
items below; cycle 5 (the cap) verifies). Deferred (recorded): the cross-process
**generation fence** (a residual writeback-vs-consume TOCTOU + a peer-overwrite window on the shared pool) and
the failed-save / failed-pool-write retry edges — all belong to **multi-client sync / durability hardening**.
Gate: **203 tests**, `buildPlugin` clean.

**Next increment (maintainer chose): orphan-comment binning.** When a reload invalidates a comment's anchor
(the commented line was deleted/rewritten so it can no longer be tracked — *anchor-lost only*, never a mere
re-indent or a still-valid marker), **delete** the comment from the pool (`store.delete`, so it's gone for the
injector + peers too) instead of showing it on an unrelated line. Decided via AskUserQuestion; deferred to its
own increment (destructive → its own careful dogfood). Runner-ups: the **visual pass**, remaining **Phase 3**
(tool window / status-bar count / settings), **multi-client sync + generation fencing** (also owns the residual
writeback-vs-consume TOCTOU), or **MODIFY-sync** (pick up an externally-edited comment's *content*). Full deferred
backlog (`CommentJson.decode` validation, GitHub identity, restore-from-tombstone, …) is in `implementation_log.local.md`.
</status>
