#!/usr/bin/env python3
import json
import os
import sys

COMMENTS_DIR = os.path.join(os.path.expanduser("~"), ".heview", "comments")
CONSUMED_DIR = os.path.join(COMMENTS_DIR, "consumed")


def format_line_content(comment):
    side = comment.get("side", "")
    prefix = "+" if side == "addition" else "-" if side == "removal" else ""
    return prefix + (comment.get("line_content") or "")  # tolerate a null line_content


def is_under_cwd(p, cwd):
    # Directory-boundary match (cwd /a/app must not capture /a/app-backend); normalize collapses `..`
    # and `//`, and stripping trailing separators lets cwd `/a/app/` (or `/`) match its descendants.
    if not isinstance(p, str) or not isinstance(cwd, str):
        return False
    np = os.path.normpath(p)
    base = os.path.normpath(cwd).rstrip("/\\")
    return np == base or np.startswith(base + os.sep)


def claim(comment, filepath, filename):
    # Atomically move the file into comments/consumed/ — the single-use claim. os.replace is atomic on
    # the same filesystem, so of two concurrent agents only the one that wins the move emits the comment;
    # the loser (file already gone) raises and returns False. Moving rather than unlinking also leaves the
    # intent signal heview's watcher reads to mark the thread "Seen". Having won the claim, rewrite the
    # tombstone with status "processed" so the consumed file reads back as Seen (best-effort).
    dest = os.path.join(CONSUMED_DIR, filename)
    try:
        os.makedirs(CONSUMED_DIR, exist_ok=True)
        os.replace(filepath, dest)
    except Exception:
        return False
    try:
        # Pretty-print (2-space indent) so the tombstone reads like an IDE-written comment (Gson
        # setPrettyPrinting); indent + ensure_ascii=False match the Node injector byte-for-byte.
        with open(dest, "w", encoding="utf-8") as fh:
            json.dump(
                {**comment, "status": "processed"}, fh, indent=2, ensure_ascii=False
            )
    except Exception:
        pass
    return True


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
            with open(filepath, "r", encoding="utf-8") as fh:
                comment = json.load(fh)
        except Exception:
            continue

        consumer = comment.get("intended_consumer")
        if consumer is not None and consumer != "codex":
            continue

        abs_path = comment.get("abs_path", "")
        if not abs_path or not is_under_cwd(abs_path, cwd):
            continue

        matched.append((comment, filepath, filename))

    if not matched:
        sys.exit(0)

    matched.sort(key=lambda x: x[0].get("created_at", ""))

    # Format each comment defensively: a matched-but-schema-incomplete file is skipped (and NOT
    # claimed), so one bad file can't abort the batch or consume a comment it never injected.
    parts = []
    for comment, filepath, filename in matched:
        try:
            rel_path = os.path.relpath(comment.get("abs_path") or "", cwd)
            block = (
                "In `"
                + rel_path
                + "` at line "
                + str(comment.get("line_number") or "")
                + ":\n```\n"
                + format_line_content(comment)
                + "\n```\n"
                + (comment.get("content") or "")
            )
        except Exception:
            continue
        # Claim after formatting; emit only what we won (see claim()).
        if not claim(comment, filepath, filename):
            continue
        parts.append(block)

    if not parts:
        sys.exit(0)

    additional_context = "\n\n".join(parts)

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
