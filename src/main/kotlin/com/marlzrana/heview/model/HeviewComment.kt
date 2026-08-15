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
 * One comment thread, persisted as `~/.heview/comments/<uuid>.json`.
 *
 * Field names and types mirror reviewa's `ReviewaComment` exactly — this is the shared `~/.heview`
 * on-disk contract the coding-agent hooks parse (plan.html §5). Do NOT rename or drop fields.
 * `@SerializedName` fixes the on-disk keys to snake_case regardless of the Kotlin property names.
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
)
