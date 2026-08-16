package com.marlzrana.heview.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ISO-8601 UTC with exactly three fractional-second digits and a trailing `Z` — the same profile as
 * JavaScript's `Date.toISOString()`.
 *
 * `created_at` must use this profile because the coding-agent hooks and the copy commands order
 * comments by lexicographic string compare (reviewa `hook.js`, `copyComments.ts`). `Instant.toString()`
 * emits variable precision (0/3/9 digits), which would break that ordering against reviewa. plan.html §5.
 */
object HeviewTime {
    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun nowIso(): String = ISO_MILLIS.format(Instant.now())

    /** Exposed for deterministic tests. */
    fun format(instant: Instant): String = ISO_MILLIS.format(instant)
}
