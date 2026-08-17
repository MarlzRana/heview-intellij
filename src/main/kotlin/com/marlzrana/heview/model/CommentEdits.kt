package com.marlzrana.heview.model

/**
 * Pure helpers for the multi-reply thread model (plan.html §5) — IDE-free so the on-disk payload is
 * unit-testable, mirroring [newFileComment]. A thread's [HeviewComment.replies] is the source of truth;
 * the hook-facing `content`/`status`/`created_at` are derived from it.
 */

/**
 * Recompute the thread's DERIVED top-level fields from its [HeviewComment.replies]:
 * - `content` = the PENDING (actionable) replies' text joined by a blank line (reviewa's join; the only
 *   thing the coding-agent hooks read — so Seen replies are excluded from injection).
 * - `status`  = PENDING if any reply is actionable, else PROCESSED.
 * - `created_at` = [now] (bumped on every change; drives cross-thread injection ordering).
 *
 * Call after any reply mutation so the persisted thread stays consistent for the hooks.
 */
fun HeviewComment.recomputed(now: String): HeviewComment {
    val actionable = replies.orEmpty().filter { it.status == CommentStatus.PENDING }
    return copy(
        content = actionable.joinToString("\n\n") { it.content },
        status = if (actionable.isEmpty()) CommentStatus.PROCESSED else CommentStatus.PENDING,
        createdAt = now,
    )
}

/**
 * The thread's replies, always non-empty: a foreign / legacy / pre-`replies` file (or one whose replies
 * were all malformed) is reconstructed as a single reply from the top-level `content`/`status`/`created_at`,
 * authored by [fallbackAuthor]. Lets the rest of the code treat every thread as a list of replies.
 */
fun HeviewComment.normalizedReplies(fallbackAuthor: String): List<HeviewReply> {
    val reps = replies
    if (!reps.isNullOrEmpty()) return reps
    // A legacy/foreign file has no per-reply id; derive a stable one from the thread uuid so repeated
    // reconstructions (across restarts, until the first save persists it) agree.
    return listOf(HeviewReply(content = content, status = status, author = fallbackAuthor, createdAt = createdAt, id = uuid))
}
