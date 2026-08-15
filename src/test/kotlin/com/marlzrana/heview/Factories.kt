package com.marlzrana.heview

import com.marlzrana.heview.model.CommentSide
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.model.IntendedConsumer

internal fun sampleComment(
    uuid: String = "u1",
    status: CommentStatus = CommentStatus.PENDING,
    side: CommentSide = CommentSide.FILE,
    intendedConsumer: IntendedConsumer? = null,
): HeviewComment = HeviewComment(
    uuid = uuid,
    status = status,
    createdAt = "2026-08-15T00:00:00.000Z",
    workspace = "/repo",
    absPath = "/repo/src/Foo.kt",
    logicalAbsPath = "/repo/src/Foo.kt",
    lineNumber = 42,
    lineContent = "val x = 1",
    side = side,
    content = "make this a const",
    intendedConsumer = intendedConsumer,
)
