# Dependency maintenance

Herald's backend and console updates are tracked together in [#385](https://github.com/dbbaskette/herald/issues/385) and [#386](https://github.com/dbbaskette/herald/issues/386). Release targets were checked against Maven Central and npm on September 9, 2026.

## Runtime and build requirements

- Java 21 or newer; the verified JDK is 21.
- Maven wrapper supplied by the repository.
- Node 22.23.1 is pinned in the root `.nvmrc` for console builds. The frontend package declares the supported Node ranges for its toolchain. Run `nvm use` from the repository root before installing frontend dependencies.
- The console's Tailwind 4 styles require Safari 16.4+, Chrome 111+, or Firefox 128+ ([Tailwind compatibility requirements](https://tailwindcss.com/docs/upgrade-guide#browser-requirements)).
- Running the packaged Java application does not require Node.

## Backend alignment

| Dependency family | Selected version | Reason |
| --- | --- | --- |
| Spring Boot | 4.1.1 | Current stable parent |
| Spring AI | 2.0.1 | Current stable BOM |
| Agent Utils and A2A | 0.12.0 | Upgrade together |
| Telegram API | 10.1.0 | Current stable |
| WireMock | 3.13.2 | Current stable; exclude 4.0 prereleases |
| Maven dependency plugin | 3.11.0 | Current stable |
| OpenAI Java core and OkHttp client | 4.49.0 | Match the Spring AI-selected core; newer standalone 4.61.0 deferred to preserve family compatibility |
| MCP SDK family | 2.0.0 | Spring AI BOM selection; standalone 2.0.1 deferred to preserve family compatibility |
| Jackson 3 | 3.1.5 | Managed dependency selection |
| SQLite JDBC | 3.53.2.1 | Boot-managed selection; standalone 3.53.4.0 deferred to keep the tested platform alignment |
| victools schema modules | 5.0.0 | Retain narrow Jackson module override: OpenAI/Anthropic still request incompatible 4.38.0 |

Spring milestone and snapshot repositories have been removed. Use stable artifacts from Maven Central. Inspect the effective dependency tree when changing the AI BOM; do not independently upgrade an SDK transport beyond the version of its core. Revisit the victools override when upstream SDKs stop requesting 4.38.0.

## Frontend alignment

| Dependency family | Selected version |
| --- | --- |
| Vue / Pinia / Vue Router | 3.5.42 / 4.0.3 / 5.3.1 |
| Vite / Vue plugin | 8.2.2 / 6.0.8 |
| Tailwind CSS / Vite integration | 4.3.3 |
| Vitest / jsdom / Vue Test Utils | 5.0.0 / 30.0.1 / 2.5.0 |
| TypeScript / vue-tsc | 6.0.3 / 3.3.11 |
| Markdown-it | 15.0.1 |

TypeScript 7.0.2 was tested but cannot run `vue-tsc` 3.3.11: the native compiler no longer exports `typescript/lib/tsc`. The console therefore uses the latest compatible TypeScript 6 release with the normal Vue typechecking command. This follows [Microsoft's TypeScript 7 guidance for embedded languages](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/); revisit the deferral when Vue's stable tooling supports the new compiler API.

Tailwind now uses its Vite integration. The old direct PostCSS/autoprefixer integration is removed; the existing theme is retained. Markdown-it type definitions are development dependencies. The lockfile records the complete set of CodeMirror and transitive versions.

## Updating and checking

Dependabot opens weekly grouped Maven and npm update PRs. Each ecosystem has separate version-update and security-update groups. There is no automatic merge. Review coupled framework/SDK versions, frontend peer constraints, and major-version migration notes before merging an update.

From the repository root:

```bash
./mvnw verify
./mvnw dependency:tree '-Dincludes=com.openai:*,io.modelcontextprotocol.sdk:*,tools.jackson.core:*,com.github.victools:*'
```

From `herald-ui/frontend`:

```bash
npm ci
npm test
npm run build
npm ls
npm audit
npm audit --omit=dev
```

`npm run build` includes Vue/TypeScript checking and writes assets to the UI module's static resources. For an isolated build, append `-- --outDir /tmp/herald-console-build`.

Before a release, run the configured Tier 0 smoke harness following [its setup guide](../../smoke/README.md). The harness exercises a live instance, can restart services, and can change memory, conversations and cron state. It needs a dedicated test configuration and credentials. The dependency-upgrade verification used isolated fixtures; credentialed live-provider smoke remains a release prerequisite.

## Follow-up compatibility work

The upgraded dependencies still compile and pass with existing Spring AI deprecation diagnostics for `defaultToolCallbacks` and `ToolCallAdvisor`. Agent Utils 0.12.0 also logs stricter question validation warnings for existing headers longer than 12 characters and zero-option question fixtures. These diagnostics remain follow-up work; successful tests do not mean the diagnostics were removed.

## Upgrade verification (September 9, 2026)

The starting revision `9b48447` passed 773 Maven tests. Its console built successfully, but 44 of 120 frontend tests failed before any dependency changes.

After the backend upgrade, `./mvnw verify` passes 775 tests with zero failures, errors or skips: core 257, persistence 231, Telegram 139, bot 93, UI 55. New fixtures cover Gemini thought-signature capture/reinjection and a real local MCP server/client round trip through Spring AI's tool callback adapter. The MCP fixture uses a random port and no external credentials. The effective dependency tree confirms the versions in the table above.


Local fixture browser checks covered the status page, skill editor, prompt diff view, routing and a streamed Markdown reply without a real bot/model connection. The existing typography and layout are preserved. Tailwind 4 now applies the existing semantic status-card colors consistently; some borders and indicators appear green, blue or cyan where the prior build appeared neutral.

The cron API contract and broader frontend contract/CI follow-ups remain tracked in [#190](https://github.com/dbbaskette/herald/issues/190) and [#387](https://github.com/dbbaskette/herald/issues/387).

The final frontend suite passes all 129 tests across 15 files, including real CodeMirror/MergeView and Markdown-it compatibility checks. `vue-tsc`, the production build and `npm ls --all` pass. Both the full and runtime-only npm audits report zero vulnerabilities, down from 16 vulnerable package entries in the starting lockfile. A clean `npm ci` also succeeds.

The diff interaction checks exposed an existing reversed revert control. Reverting now copies the bundled baseline into the editable draft, preserves the baseline, emits the updated draft and enables saving. Read-only diffs expose no revert controls. The updated Vue compiler also exposed invalid nested buttons in the conversation list; each neutral row now contains sibling native selection and delete buttons for independent keyboard activation. The bundled-skill delete action has an explicit read-only guard and accessible name.

The production build still reports its existing large main-chunk and mixed static/dynamic approvals-import warnings. Those optimization opportunities remain separate from this dependency upgrade.

The final Java package build succeeds with the upgraded console assets. The packaged HTML, CSS and JavaScript were checked against the generated production files byte-for-byte.
