# Optional interactive browser

Herald's existing `web_fetch` and `web_search` remain lightweight HTTP tools.
Interactive browsing is a separate, **disabled-by-default** capability. Enable it
only on a host intentionally provisioned to run a disposable Chromium process.

```yaml
herald:
  browser:
    enabled: true
    allowed-origins: https://example.org,https://static.example.org
    headless: true
    max-sessions: 2
    idle-seconds: 300
    # Optional explicit executable; never point to a user profile.
    executable-path: /path/to/chromium
```

Origins include scheme and port; no wildcard or implicit subdomain access exists.
Every resource must belong to an allowed origin. Private/loopback/link-local
network destinations are rejected even if configured. Tests have a package-private
loopback exception unavailable through production configuration. This first
implementation chooses local Playwright, not attachment to the user's browser or
a remote browser profile. A remote browser can instead be a separately configured,
approved MCP integration; local browser enablement is not required for MCP.

## Provisioning

The Java Playwright driver is packaged; its browser binaries and OS dependencies
are **not installed or downloaded by Herald startup or tool calls**. With the
matching Playwright Java version (currently 1.62.0), provision Chromium explicitly
using the [official browser installation instructions](https://playwright.dev/java/docs/browsers)
or specify a compatible executable path. The standard Playwright CLI entry point
is `com.microsoft.playwright.CLI` with arguments `install chromium`. Keep the Java
driver and installed browser versions compatible. Playwright documents the supported
[OS/runtime requirements](https://playwright.dev/java/docs/intro).

Missing executables produce a tool failure without activating another browser or
installing software. Disabled mode creates no browser beans, process, scheduler,
probe or registered tools. Cloud Foundry/Tanzu's default headless/server profile
should leave this disabled. Running local browsers in cloud containers requires
explicit compatible image/buildpack libraries, Chromium sandbox support, process
resources and network egress policy; a generic desktop-less Java buildpack is not
sufficient. This feature does not change the default deployment footprint into a
mandatory desktop environment.

## Tools and model screenshots

- `browser_open` creates a fresh session for the active conversation.
- `browser_navigate`, `browser_click`, `browser_type`, `browser_select` act on it.
- `browser_read` returns bounded visible text plus observed selectors for controls.
- `browser_screenshot` captures the 1280×720 viewport; `BrowserScreenshotAdvisor`
  attaches PNG bytes as actual image media to the next model request. A
  vision-capable provider/model is required; base64 text is not presented as a
  substitute for vision.
- `browser_close` discards context state. Always close at task completion.

Screenshots are held in memory, at most one pending image per conversation and
4 MiB per image, consumed by the next model request or removed on session close
or expiry. They are not written to files or imported into persistent chat memory
by the screenshot advisor. As with other vision input, the selected model provider
receives the image. Each session allows 100 tool accesses and 300 HTTP requests; at most eight sessions
can be configured. Operations/navigation time out, idle contexts expire (default
five minutes, maximum thirty), and all Playwright operations run on one owner
thread. Shutdown closes contexts and the launched browser. No user profiles,
cookie jars, storage-state import, persistent downloads or saved credentials are
supported. Playwright's [isolated browser contexts](https://playwright.dev/java/docs/browser-contexts)
provide the underlying session separation.

## Approval and boundaries

Opening/navigating and **every** click/type/select require approval. This deliberately
covers actions that may submit forms, send messages, purchase or delete instead of
trying to infer consequences from button wording. Web requests use the existing
approval inbox and SSE prompt; they never use the legacy web auto-approval branch.
Telegram uses the existing approval flow. Unattended/system turns and missing
approval surfaces fail closed. The web approval inbox previews the exact typed value without logging it. Telegram
approval text redacts typed values because the legacy approval gate logs descriptions;
review the intended value in the conversation before approving a Telegram text action.

Cancelled/queued operations check a cancellation token before navigation, DOM
actions and network dispatch, then tear down their session. An action already
dispatched to the browser cannot be undone by cancellation.

The policy blocks redirects rather than silently following them, checks every
HTTP resource, blocks service workers and WebSockets, closes popups, dismisses
native dialogs and cancels downloads. Non-GET/HEAD requests are blocked except
while executing an approved click/type/select. There is no arbitrary-JavaScript,
file upload or cookie-import tool. The internal DOM reader is fixed code.

These controls reduce unintended browser actions; they are not a replacement for
OS network/process containment. Allowed sites execute JavaScript and GET endpoints
can themselves have side effects. DNS validation and a later connection are not
an atomic DNS pin, so configure only trusted origins and restrict browser egress
at the host/container level. Approval cannot prove a malicious page's DOM remains
unchanged between preview and click. Do not use this browser for untrusted-site
credential handling or assume it is a hostile-code sandbox.

## Verification without real sites

Unit tests exercise origin restrictions, disabled wiring and fail-closed approvals.
`BrowserFixtureTest` runs real Chromium against in-process loopback HTTP fixtures,
using fresh contexts and a scripted ChatModel through the actual ChatClient tool
loop. It covers typing/select/click, image media arriving at the model, denied
mutations, blocked origin/redirect, session isolation/cleanup/expiry and missing
browser failures. It is opt-in because standard builds must not require a browser:

```sh
mvn -pl herald-core -am -Dtest='Browser*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dherald.browser.fixture-executable=/absolute/path/to/chromium test
```

Use a dedicated compatible browser binary. The tests never open real sites or
attach to an existing profile. Default tests still exercise disabled and security
behavior without browser binaries installed.
