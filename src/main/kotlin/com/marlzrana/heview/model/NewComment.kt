package com.marlzrana.heview.model

import java.util.UUID

/**
 * Builds a v1 file comment: `side = FILE` (no diff-side detection yet), `logical_abs_path == abs_path`
 * (no plan remap yet), `status = PENDING`, and a 1-based `line_number` from a 0-based editor line.
 *
 * Pure and IDE-free so the persisted payload — the shared-contract fields the coding-agent hooks
 * match on (`abs_path`/`logical_abs_path` by `cwd` prefix) and render (`line_number`, `line_content`)
 * — is unit-testable without a running IDE.
 */
fun newFileComment(
    workspace: String,
    absPath: String,
    line0Based: Int,
    lineContent: String,
    content: String,
    createdAt: String,
    uuid: String = UUID.randomUUID().toString(),
): HeviewComment = HeviewComment(
    uuid = uuid,
    status = CommentStatus.PENDING,
    createdAt = createdAt,
    workspace = workspace,
    absPath = absPath,
    logicalAbsPath = absPath,
    lineNumber = line0Based + 1,
    lineContent = lineContent,
    side = CommentSide.FILE,
    content = content,
)
