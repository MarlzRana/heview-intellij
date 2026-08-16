#!/usr/bin/env python3
import json
import os
import sys

COMMENTS_DIR = os.path.join(os.path.expanduser("~"), ".heview", "comments")


def format_line_content(comment):
    side = comment.get("side", "")
    prefix = "+" if side == "addition" else "-" if side == "removal" else ""
    return prefix + comment.get("line_content", "")


def main():
    try:
        data = json.loads(sys.stdin.read())
    except Exception:
        sys.exit(0)

    cwd = data.get("cwd", "")
    if not cwd or not os.path.isdir(COMMENTS_DIR):
        sys.exit(0)

    files = [f for f in os.listdir(COMMENTS_DIR) if f.endswith(".json")]
    if not files:
        sys.exit(0)

    matched = []
    for filename in files:
        filepath = os.path.join(COMMENTS_DIR, filename)
        try:
            with open(filepath, "r") as fh:
                comment = json.load(fh)
        except Exception:
            continue

        consumer = comment.get("intended_consumer")
        if consumer is not None and consumer != "codex":
            continue

        abs_path = comment.get("abs_path", "")
        if not abs_path or not abs_path.startswith(cwd):
            continue

        matched.append((comment, filepath))

    if not matched:
        sys.exit(0)

    matched.sort(key=lambda x: x[0].get("created_at", ""))

    parts = []
    for comment, _ in matched:
        rel_path = os.path.relpath(comment["abs_path"], cwd)
        formatted = format_line_content(comment)
        parts.append(
            "In `"
            + rel_path
            + "` at line "
            + str(comment["line_number"])
            + ":\n```\n"
            + formatted
            + "\n```\n"
            + comment["content"]
        )

    additional_context = "\n\n".join(parts)

    for _, filepath in matched:
        try:
            os.unlink(filepath)
        except Exception:
            pass

    output = {
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": additional_context,
        }
    }
    sys.stdout.write(json.dumps(output))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        sys.exit(0)
