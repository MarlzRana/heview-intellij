package com.marlzrana.heview.model

/**
 * The reply / edit / re-pend transitions of the comment state machine (plan.html §5), as pure
 * functions on [HeviewComment] — IDE-free so the on-disk result is unit-testable, mirroring
 * [newFileComment].
 *
 * All three land the comment in [CommentStatus.PENDING] ("actionable"): reviewa auto-revives a Seen
 * (processed) comment the moment it is edited, replied to, or re-pended, and PENDING is the only
 * status ever written to `comments/` (PROCESSED lives only in `processed/` as a tombstone). Each also
 * bumps `created_at` — the field that drives injection ordering — so a revived comment sorts as the
 * newest actionable note. Persistence + tombstone cleanup are the caller's job (see [com.marlzrana.heview.storage.CommentStore]).
 */

/**
 * Append [replyText] to the thread and revive it. reviewa persists a thread's `content` as its
 * actionable comment texts joined by a blank line; a reply is one more text, so it appends `"\n\n" +
 * replyText`. Byte-compatible with reviewa's on-disk `content` for a single-author thread.
 */
fun HeviewComment.withReply(replyText: String, createdAt: String): HeviewComment =
    copy(content = "$content\n\n$replyText", createdAt = createdAt, status = CommentStatus.PENDING)

/** Replace the thread's `content` and revive it (reviewa's edit-a-processed-comment auto-repends). */
fun HeviewComment.withContent(newContent: String, createdAt: String): HeviewComment =
    copy(content = newContent, createdAt = createdAt, status = CommentStatus.PENDING)

/** Re-pend: revive a Seen comment to actionable, bumping `created_at`; the content is untouched. */
fun HeviewComment.revived(createdAt: String): HeviewComment =
    copy(createdAt = createdAt, status = CommentStatus.PENDING)
