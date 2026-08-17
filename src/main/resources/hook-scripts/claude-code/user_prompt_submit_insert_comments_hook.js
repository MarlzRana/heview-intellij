#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const COMMENTS_DIR = path.join(require('os').homedir(), '.heview', 'comments');
const PROCESSED_DIR = path.join(COMMENTS_DIR, 'processed');

function formatLineContent(comment) {
	const prefix = comment.side === 'addition' ? '+' : comment.side === 'removal' ? '-' : '';
	return prefix + comment.line_content;
}

// Directory-boundary match (cwd `/a/app` must not capture `/a/app-backend`). Guard non-strings (a
// foreign pool file may carry a null/missing path), normalize to collapse `..`/`//`, and strip
// trailing separators so cwd `/a/app/` (or `/`) still matches its descendants.
function isUnderCwd(p, cwd) {
	if (typeof p !== 'string' || typeof cwd !== 'string') return false;
	const np = path.normalize(p);
	const base = path.normalize(cwd).replace(/[/\\]+$/, '');
	return np === base || np.startsWith(base + path.sep);
}

// Claim a comment by atomically moving its file into `comments/processed/`. The move IS the single-use
// claim: renameSync is atomic on the same filesystem, so of two concurrent agents only the one that
// wins the rename emits the comment — the loser's rename throws (ENOENT) and it skips. Moving (rather
// than unlinking) also leaves the intent signal heview's watcher reads to mark the thread "Seen".
// Having won the claim, rewrite the tombstone with status:"processed" so the consumed file reads back as
// Seen (best-effort — the move already consumed it, so a failed rewrite just leaves it as pending).
function claim(comment, filePath, filename) {
	const dest = path.join(PROCESSED_DIR, filename);
	try {
		fs.mkdirSync(PROCESSED_DIR, { recursive: true });
		fs.renameSync(filePath, dest);
	} catch {
		return false;
	}
	try {
		// Pretty-print (2-space indent) so the tombstone reads like an IDE-written comment (Gson
		// setPrettyPrinting); matches the Python injector byte-for-byte.
		fs.writeFileSync(dest, JSON.stringify({ ...comment, status: 'processed' }, null, 2));
	} catch {}
	return true;
}

async function main() {
	const chunks = [];
	for await (const chunk of process.stdin) {
		chunks.push(chunk);
	}
	const input = JSON.parse(Buffer.concat(chunks).toString());
	const cwd = input.cwd;

	if (!cwd || !fs.existsSync(COMMENTS_DIR)) {
		process.exit(0);
	}

	const files = fs.readdirSync(COMMENTS_DIR).filter(f => f.endsWith('.json'));
	if (files.length === 0) {
		process.exit(0);
	}

	const matchedComments = [];

	for (const file of files) {
		const filePath = path.join(COMMENTS_DIR, file);
		let comment;
		try {
			comment = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
		} catch {
			continue;
		}

		const consumer = comment.intended_consumer;
		if (consumer && consumer !== 'claude_code') {
			continue;
		}

		const matchPath = comment.logical_abs_path || comment.abs_path;
		if (!matchPath || !isUnderCwd(matchPath, cwd)) {
			continue;
		}

		matchedComments.push({ comment, filePath, file });
	}

	if (matchedComments.length === 0) {
		process.exit(0);
	}

	matchedComments.sort((a, b) => (a.comment.created_at || '').localeCompare(b.comment.created_at || ''));

	const parts = [];
	for (const { comment, filePath, file } of matchedComments) {
		const displayPath = isUnderCwd(comment.abs_path, cwd) ? path.relative(cwd, comment.abs_path) : comment.abs_path;
		const formatted = formatLineContent(comment);
		const block = 'In `' + displayPath + '` at line ' + comment.line_number + ':\n```\n' + formatted + '\n```\n' + comment.content;
		// Claim after formatting, emit only if we won it: a formatting error can't leave a comment
		// claimed-but-not-injected, and two concurrent agents never inject the same comment.
		if (!claim(comment, filePath, file)) continue;
		parts.push(block);
	}

	if (parts.length === 0) {
		process.exit(0);
	}

	const additionalContext = parts.join('\n\n');

	const output = {
		hookSpecificOutput: {
			hookEventName: 'UserPromptSubmit',
			additionalContext,
		},
	};
	process.stdout.write(JSON.stringify(output));
}

main().catch(() => process.exit(0));
