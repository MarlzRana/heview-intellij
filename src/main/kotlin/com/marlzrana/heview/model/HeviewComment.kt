package com.marlzrana.heview.model

import com.google.gson.annotations.SerializedName

/** Which side of a diff the line sits on. Matches reviewa's `CommentSide`; v1 only ever emits [FILE]. */
enum class CommentSide {
    @SerializedName("file") FILE,
    @SerializedName("addition") ADDITION,
    @SerializedName("removal") REMOVAL,
}

/** Persisted status. `repending` is a UI-only state that persists as [PENDING] (see plan.html §5). */
enum class CommentStatus {
    @SerializedName("pending") PENDING,
    @SerializedName("processed") PROCESSED,
}

/** The agent a comment is routed to. Gemini was dropped, so Claude Code is the only value. */
enum class IntendedConsumer {
    @SerializedName("claude_code") CLAUDE_CODE,
}

/**
 * One reply within a comment thread (heview extension to the shared schema).
 *
 * A thread ([HeviewComment]) holds an ordered list of these, each with its own status so the user can
 * edit / delete / re-pend replies independently — matching reviewa's per-comment UI, which reviewa
 * keeps only in memory. heview persists them (it reloads the pool on restart) via [HeviewComment.replies].
 * The thread's top-level `content`/`status` stay DERIVED from these replies (see `recomputed`) so the
 * coding-agent hooks — which read only `content` — need no change.
 */
data class HeviewReply(
    @SerializedName("content") val content: String,
    @SerializedName("status") val status: CommentStatus,
    @SerializedName("author") val author: String,
    @SerializedName("created_at") val createdAt: String,
    // Stable per-reply identity so an edit / delete / re-pend targets the right reply even after a
    // concurrent change flips its status or edits its text (its other fields are all mutable). Generated
    // once, then persisted and preserved across mutations; heview-internal, so hooks/reviewa ignore it.
    @SerializedName("id") val id: String = java.util.UUID.randomUUID().toString(),
)

/**
 * One comment thread, persisted as `~/.heview/comments/<uuid>.json`.
 *
 * The top-level fields mirror reviewa's `ReviewaComment` exactly — the shared `~/.heview` on-disk
 * contract the coding-agent hooks parse (plan.html §5); do NOT rename or drop them. `content` is the
 * PENDING replies joined by a blank line and `status` is PENDING iff any reply is actionable — both
 * DERIVED from [replies]. [replies] is a heview-only superset field a foreign/legacy file may omit
 * (then the thread is one reply reconstructed from `content`). `@SerializedName` pins snake_case keys.
 */
data class HeviewComment(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("status") val status: CommentStatus,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("workspace") val workspace: String,
    @SerializedName("abs_path") val absPath: String,
    @SerializedName("logical_abs_path") val logicalAbsPath: String,
    @SerializedName("line_number") val lineNumber: Int,
    @SerializedName("line_content") val lineContent: String,
    @SerializedName("side") val side: CommentSide,
    @SerializedName("content") val content: String,
    @SerializedName("intended_consumer") val intendedConsumer: IntendedConsumer? = null,
    @SerializedName("replies") val replies: List<HeviewReply>? = null,
)
