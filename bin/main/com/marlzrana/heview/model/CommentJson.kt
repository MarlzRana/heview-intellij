package com.marlzrana.heview.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * (De)serializes [HeviewComment] to the shared `~/.heview` JSON contract (plan.html §5).
 *
 * Keys are snake_case via `@SerializedName`, so files are byte-compatible with reviewa's schema and
 * readable by the same coding-agent hooks. Gson omits null fields, so an absent `intended_consumer`
 * is left out entirely — matching reviewa's optional field.
 */
object CommentJson {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(comment: HeviewComment): String = gson.toJson(comment)

    fun decode(text: String): HeviewComment = gson.fromJson(text, HeviewComment::class.java)
}
