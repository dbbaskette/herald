# Integrations and execution safety

Scope: #192, #245, #246, #247, #249, #255. #248 is deferred at the user's request; #250–#254 are closed.

1. Make clean Maven packaging generate and verify the console, enforce the same build in CI, and repair launcher artifact assumptions.
2. Register explicitly allowlisted MCP callbacks and exercise the production model/tool loop against an isolated MCP fixture.
3. Add opt-in browser tools with explicit origin controls, isolated contexts, approval for actions, screenshots and cleanup; no browser installation required for ordinary startup.
4. Bound Spring AI's existing loop with shared step/token limits, an overall deadline and optional estimated cost limits. Apply to configured main clients and profile clients; test actual tool iterations and cancellation.
5. Exercise real task delegation, role isolation, parallel failure containment and synthesis; repair gaps using the existing task repository/framework.
6. Retain legacy SQLite facts explicitly for compatibility and manual reference. File memory remains canonical; distinguish the stores in the console and document backup/export without silent migration or deletion.

Verification: focused fixtures at functional milestones, then the full relevant Java and frontend suites plus clean packaging. Review the final diff, push one PR, merge after checks, and verify issue closure. No live bot, credentials or user data are used for tests.
