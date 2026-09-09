# Context compaction

Herald currently uses its existing Spring AI ChatMemory API. The future Session API migration remains separate from this interim implementation (#270, #272).

Set `herald.memory.compaction-strategy` to `recursive-summary` (default) or `sliding-window`. Both modes trigger at the estimated 80% context ceiling; `/compact` requests a smaller target manually.

Recursive summary replaces older complete user turns with a tagged synthetic user/assistant summary turn. The next summary includes the prior summary, and both SQLite persistence and prompt count limits retain the tags and synthetic turn. The assistant summary also updates the existing continuity files. If the summary model fails, is unavailable, or returns empty text, history remains unchanged.

Sliding window drops older complete user turns without a summary-model call or a new hot.md summary. Existing hot.md content still follows the usual HotMdAdvisor behavior; changing strategy does not erase that file.

Eviction snaps backwards to a real user message, keeping that user message and its full assistant/tool exchange. System messages are preserved. The latest user turn is never partially removed. Consequently the token and message targets are soft: a large latest turn or an insufficient earlier boundary may remain above the configured target. Token accounting is an estimate that includes tool arguments and responses; it is not a provider tokenizer or a hard input-size guarantee.

The configured repository-backed memory synchronizes updates and compaction within one application process. SQLite replacements are transactional, so failed writes roll back instead of leaving partial history. Separate Herald processes must not concurrently compact the same conversation; cross-process coordination is outside this interim implementation.
