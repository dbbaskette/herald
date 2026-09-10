# Frontend TypeScript compiler compatibility

The console uses TypeScript 7.0.2 through the Vue maintainer's `typescript-native-bridge`, pinned as the npm alias `typescript` at `6.0.3-bridge.16.tsgo.7.0.2`. The 6.0.3 prefix describes the compatible JavaScript API surface; the checker is the native 7.0.2 engine. This is a maintained fork, not the stock Microsoft TypeScript package.

Stock TypeScript 7.0.2 fails with vue-tsc 3.3.11 because it does not export `typescript/lib/tsc`. The bridge preserves the API expected by Vue tooling without dropping template typechecking or retaining a second legacy compiler. Its native platform package is recorded in package-lock.json; use the supported Node version and `npm ci` with optional dependencies enabled.

The package.json alias plus `overrides.typescript: "$typescript"` follows the [maintainer's npm installation guidance](https://github.com/johnsoncodehk/typescript-native-bridge#npm). [Vue's upstream compatibility discussion](https://github.com/vuejs/language-tools/issues/5381) explains the current boundary and future native integration direction.

The canonical package/CI command is `./scripts/build.sh` from the repository root; it runs these checks through Maven automatically. See [Building and installing](building.md). For a frontend-only development check, run from `herald-ui/frontend`:

```sh
npm ci
npm run test:typecheck
npm test
npm run build
```

`test:typecheck` checks the installed bridge/engine version and creates and cleans temporary source fixtures. It proves a valid Vue component with an alias import passes, then verifies that both a TypeScript assignment error and a Vue template property error are rejected. The production build still runs `vue-tsc -b` before Vite. Dependency updates must preserve these diagnostics, not merely make the build command exit successfully.

Keep the bridge version exact and update it deliberately with the lockfile. Re-evaluate removal when the published Vue toolchain supports stock TypeScript's native API; do not replace the alias with stock TypeScript based only on its version number. Browser assets still use Vite's normal build path. Linux/Windows native binaries are supplied by the dependency; local verification for this migration was on macOS arm64.
