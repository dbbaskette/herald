# Building and installing Herald

Use **Java 21** and **Node 22.23.1** with its bundled npm. `.nvmrc` selects the tested Node release (`nvm install && nvm use`); the build also accepts the frontend package's supported Node ranges and rejects older runtimes before installing dependencies. Native TypeScript compiler packages require optional dependencies, which the build explicitly installs. No `.env`, credentials, running bot, or personal integrations are required to build.

From a fresh checkout:

```sh
nvm install
nvm use
./scripts/build.sh
```

This is the authoritative local and CI build: `mvnw --batch-mode clean verify`. `make build`, `make build-ui`, `make build-all`, `make verify`, and `./run.sh build` delegate to it. The `run.sh build` branch exits before credential loading, skill bootstrap, and service controls.

The UI's ordinary Maven lifecycle owns the console build, so `./mvnw verify` and `./mvnw -pl herald-ui -am package` also regenerate assets. During `generate-resources`, it runs lockfile-based `npm ci`, the [TypeScript/Vue diagnostic probe](frontend-typechecking.md), and the production build/typecheck. The Maven `test` phase runs the existing Vitest suite and fails on any test failure. `verify` compares every generated asset with the executable JAR, byte for byte, and rejects missing or extra stale assets. Frontend failures propagate as Maven failures; CI uses the same command and never skips tests.

Vite writes to `herald-ui/target/generated-resources/console/static`; Maven copies that directory to `BOOT-INF/classes/static/`. Compiled resources under `src/main/resources/static` are no longer versioned or packaged. Both the generated directory and a previous compiled static directory are refreshed, so even a non-clean build cannot retain old hashed chunks. Public images remain source files under `frontend/public`.

Maven's explicit `-DskipTests` or `-Dmaven.test.skip=true` options skip Vitest as well as Java tests for a deliberate local development build; assets and typechecks still run. These options are not part of the documented release/CI path. Direct `spring-boot:run` is a development convenience; use a verified executable JAR for deployment.

## Artifacts and local services

After verification, find the current executable without hardcoding a version:

```sh
./scripts/find-artifact.sh bot
./scripts/find-artifact.sh ui
java -jar "$(./scripts/find-artifact.sh ui)"
```

The finder accepts exactly one module executable and rejects an absent or ambiguous target directory; run the clean build to remove stale versions. `make install` (bot), `make install-ui`, and `make install-all` run the same verification prerequisite before copying artifacts and installing/starting macOS LaunchAgents. Installation is an explicit service action, not part of the build. Bot installation still requires its configured environment. `make logs-ui` follows the UI launch-agent log.

LaunchAgent ports are explicit: bot `127.0.0.1:8081`, UI `127.0.0.1:8080`. Keep loopback defaults and use the documented [remote-access setup](remote-access.md) for access from other devices.

## CI

`.github/workflows/verify.yml` installs Java 21 and the `.nvmrc` Node version, caches downloaded Maven/npm artifacts, and runs `./scripts/build.sh` on pull requests and pushes to main. CI also supplies its installed Chrome executable to run the isolated browser fixtures; local builds can opt in with `./scripts/build.sh "-Dherald.browser.fixture-executable=/path/to/chrome"`. Dependency caches do not replace `npm ci` or generated assets. The job's exit status enforces both frontend regressions and Maven verification. Branch protection, if desired, should require the workflow's `verify` job; repository branch rules are managed separately.
