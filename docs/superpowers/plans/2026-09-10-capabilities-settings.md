# Capability and Settings Completion Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete #388 and #389 by shipping their nine open child scopes (#402–#410) with one capability contract and one truthful settings contract.

**Architecture:** Add a small shared capability model in `herald-core`, resolve provider and integration states once, and reuse those states in preflight, runtime bean/tool gating, the bot status API, and the console. Keep settings persistence in `herald-ui`, but represent each setting with saved/effective/source/restart metadata so a database write is never confused with a live runtime change.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.1, SQLite/JDBC, Vue 3, Pinia, Vitest.

**Spec:** GitHub issues #388, #389, #402–#410.

## Global Constraints

- Preserve Telegram plus file memory as the default personal-assistant experience.
- Optional integrations must be blank-safe and must not probe, schedule, or expose tools while disabled.
- Keep the existing A2A client implementation and distinguish it from server publication (#266).
- Preserve MeetingNotes recap and Reminders fan-out as independent flags when ingestion is enabled.
- Settings updates are atomic and startup-only values are explicitly marked restart-required.
- Tests use local fixtures and mocks only; no credentials, personal data, or live integrations.

## Task 1: Provider and Telegram capability resolution (#402)

- [ ] Add normalized capability/provider value objects and resolution helpers under `herald-core/src/main/java/com/herald/config/`.
- [ ] Update preflight and doctor to validate the selected provider's real requirements and report the same normalized state.
- [ ] Make Telegram bean creation depend on nonblank token and chat ID while allowing no-Telegram assistant/task configurations.
- [ ] Add provider-only, fallback, blank-Telegram, and task-mode tests.

## Task 2: Optional integration lifecycle and tool gating (#403)

- [ ] Add explicit MeetingNotes and persistence/cron enablement decisions with backward-compatible defaults.
- [ ] Gate MeetingNotes catalog, controller, worker, recovery schedule, catch-up job, and tool exposure at bean creation.
- [ ] Remove the JDBC dependency from `GwsTools` and register it only when the CLI capability is usable.
- [ ] Prove disabled capabilities create no probes, workers, schedules, controllers, or callbacks.

## Task 3: Capability API and setup/status UI (#404)

- [ ] Publish normalized capability state, message, and setup metadata from the bot.
- [ ] Proxy/stream the capability snapshot without converting unknown/unavailable states to healthy defaults.
- [ ] Render healthy, disabled, unconfigured, unavailable, and failed distinctly.
- [ ] Let users skip Google, Obsidian, and MeetingNotes setup without leaving the checklist incomplete.
- [ ] Add backend and frontend state/rendering tests.

## Task 4: Provider/capability documentation (#405)

- [ ] Add a checked-in provider and capability matrix including keys, base URLs, model variables, properties, defaults, precedence, fallback, restart, and skip behavior.
- [ ] Reconcile README, getting-started, example YAML, NORTHSTAR, and MeetingNotes recovery docs.
- [ ] Add a lightweight documentation contract check to CI/build verification.

## Task 5: Explicit A2A client lifecycle (#406)

- [ ] Add an explicit backward-compatible A2A client-enabled setting.
- [ ] Omit remote resolver/executor/references when disabled; retain local subagents.
- [ ] Extend the local JSON-RPC fixture through actual `TaskTool` execution and final synthesis for success and bounded failure.
- [ ] Document runtime disablement and the separate A2A server scope.

## Task 6: Transactional settings API (#407, #409)

- [ ] Replace map-only PUT responses with typed saved values, validation errors, and application metadata.
- [ ] Validate timezone and context limits before writes; apply multi-field updates transactionally.
- [ ] Return saved/effective/source/restart status per supported setting using an explicit runtime snapshot contract.
- [ ] Test validation, rollback, write failures, source precedence, and post-restart effective values.

## Task 7: Draft-safe settings experience (#408, #410)

- [ ] Separate load and save errors in the Pinia store and return an explicit save outcome.
- [ ] Preserve drafts on failure, validate inline, prevent duplicate submissions, and retry the same draft.
- [ ] Show saved/effective/source/restart state and correct Google setup instructions to `.env`/`docs/gws-setup.md`/`./run.sh all`.
- [ ] Add frontend failed-save, retry, validation, duplicate-submit, effective-state, and copy regressions.

## Task 8: Integration and completion

- [ ] Run focused tests at each completed slice and resolve failures.
- [ ] Review every issue acceptance item against code and tests.
- [ ] Run `./scripts/build.sh` with isolated browser fixtures and the complete frontend/backend suites.
- [ ] Open one PR closing #388, #389, and #402–#410; merge only after GitHub checks pass.
