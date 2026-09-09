# Console draft safety

Skills and Prompts ask **Save / Discard / Stay** before switching files, reloading a file, creating a skill, or navigating away with unsaved changes. Browser refresh and tab close use the browser's own unsaved-changes warning. Save failures keep the draft open; typing during a save remains unsaved. Drafts are held in memory, not browser storage, because prompts may contain personal information. Accepting the browser's leave warning or terminating the browser loses unsaved drafts.

An external edit causes a conflict when saving or deleting. The comparison shows the latest file on the left and your editable draft on the right. **Discard draft and use latest** replaces the draft. **Keep draft against latest** acknowledges the displayed file version and retains your edits for a separate Save; you can merge changes in the right pane first. Another external edit triggers another conflict. Reloading a dirty file first requires an explicit draft decision.

## API clients

GET `/api/skills/{name}` and `/api/prompts/{name}` include a strong content `ETag`. Send that exact value in `If-Match` with PUT or DELETE. Existing request bodies are unchanged. A missing header returns **428**, and a mismatched version returns **412** with JSON `content` and `version` plus the latest ETag. Clients must show the conflict and obtain a deliberate reconciliation before retrying. Wildcard/unconditional overwrites are not accepted. Read an absent personal context or bundled prompt normally to obtain the initial version before creating its override. Skill POST remains a create-only operation and returns 409 for an existing directory.

Writes are atomic and API mutations are serialized per controller. The version check detects disk edits made since the read; an unrelated operating-system writer that changes the file during the final compare/write interval cannot be locked by this API. Cooperating external editors should use the versioned API. Validation errors still return 422 without changing skill files. Prompt overrides still require a bot restart, except personal context.
