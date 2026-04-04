## What to build

Treat this as a **new Codex feature module/page** inside the existing Android app, backed by a **single long-lived WebSocket client** to `codex app-server`, not stdio. `codex app-server` supports JSON-RPC 2.0 over WebSocket text frames, persists threads, streams `turn/*` and `item/*` events, and has first-class approval request/response flows for commands and file changes. ([OpenAI Developers][1])

Your requested UX maps cleanly to app-server primitives:

* **Homepage → projects list**: your app-owned project registry, not a Codex primitive.
* **Project selected → past threads + new thread**: use `thread/list` filtered by `cwd`.
* **Thread screen**: `thread/read` for history, `thread/resume` when opening a live thread, `turn/start` to send prompts.
* **Approvals over WebSocket**: handle server-initiated JSON-RPC approval requests and answer them with the selected decision payload.
* **Tool calls + reasoning traces**: render streamed `item/*` events, especially `reasoning`, `commandExecution`, `fileChange`, `mcpToolCall`, `dynamicToolCall`, `webSearch`, and `agentMessage` deltas. ([OpenAI Developers][1])

## Core architectural decision

Do **not** model this as “Android talks directly to Codex internals.” Model it as:

1. Android feature module
2. `CodexWsClient` transport layer
3. `CodexSessionRepository`
4. project/thread view models
5. app-owned local registry for approved projects
6. Codex server process launched for local dev only

The server is explicitly meant for “local development or debugging” and the `ws://` listener is documented as experimental / dev-oriented, so keep your integration defensive and version-pinned. ([OpenAI Developers][2])

## Recommended scope split

### 1. App-owned project model

Codex does not provide “projects” as a top-level resource. Use your own persistent project registry:

* `id`
* `name`
* `localPath`
* `codexCwd`
* `permissionsPreset` (`workspaceWrite` default, optional `dangerFullAccess`)
* `archived`
* `displayOrder`

This registry should be the source of truth for the homepage list.

For your current requirement, only register **approved cloned git folders** under the server’s working area. Then pass the selected project path as `cwd` on `thread/start`, `thread/resume`, and `turn/start`. App-server thread listing also supports filtering by exact `cwd`, which is what you want for “show past threads for this project.” ([OpenAI Developers][1])

### 2. Thread model

Use Codex’s stored threads directly.

Relevant endpoints:

* `thread/start` for a new conversation
* `thread/resume` when reopening an existing thread
* `thread/read` when you want stored history without loading/subscribing
* `thread/list` for the project thread index
* `thread/archive` / `thread/unarchive` later if needed
* `thread/rollback` and `thread/compact/start` can be phase-2 features, not MVP ([OpenAI Developers][1])

### 3. Live turn model

Each user message becomes `turn/start`. The server responds with the initial turn and then streams notifications. Keep reading the socket continuously for:

* `turn/started`
* `turn/completed`
* `turn/plan/updated`
* `turn/diff/updated`
* `item/started`
* `item/completed`
* `item/agentMessage/delta`
* `serverRequest/resolved` ([OpenAI Developers][1])

This is the right shape for a reactive Android screen.

## Screen-by-screen plan

## A. Homepage: Projects

App-owned page.

Show:

* approved projects
* current Codex connection state
* optional server mode badge: local sandbox / full access

Actions:

* select project
* optionally refresh threads count per project
* later: add/remove approved project folders

Implementation note:
Do not ask Codex to “discover” projects. That is your app’s job.

## B. Project detail: Threads list

When a project opens:

1. ensure WebSocket is connected
2. complete JSON-RPC handshake:

   * `initialize`
   * `initialized`
3. call `thread/list` with:

   * `cwd = project.localPath`
   * `sourceKinds = ["appServer"]` if you want to isolate threads created by this integration
   * pagination as needed
   * sort by `updated_at` if you want recent-first UX ([OpenAI Developers][1])

Use `thread/read(includeTurns=false)` only if you need extra per-thread metadata on demand.

Actions:

* open thread
* create new thread

## C. Thread screen

When opening an existing thread:

1. `thread/read(threadId, includeTurns=true)` to hydrate full stored state
2. `thread/resume(threadId, cwd?, model?, personality?)` if you want the live connection subscribed again
3. subscribe UI to streamed notifications

When starting a new thread:

1. `thread/start`
2. include:

   * `cwd`
   * model
   * approval policy
   * sandbox baseline
   * `serviceName` for integration tagging/telemetry ([OpenAI Developers][1])

Thread screen UI should have:

* top app bar with back
* thread title / project name
* connection status
* scrollable mixed event timeline
* sticky approval sheet/banner
* composer
* interrupt button while turn is active

## WebSocket transport plan

Use a **single connection per signed-in app session** unless you have a strong reason to isolate per-thread sockets.

Why:

* handshake is per connection
* the connection can stay subscribed to loaded threads
* approvals are server-initiated on the same connection
* easier global reconnect and backpressure handling

Transport specifics from the docs:

* WebSocket uses **one JSON-RPC message per text frame**
* requests can be rejected with `-32001 "Server overloaded; retry later."`
* clients should retry with **exponential backoff + jitter**
* no requests before `initialize` + `initialized` handshake succeeds ([OpenAI Developers][1])

Implementation rules:

* central outbound request queue
* monotonic JSON-RPC ids
* pending request map
* inbound dispatcher that branches:

  * response to existing request id
  * notification
  * server-initiated request requiring client response

I would make server-initiated requests a first-class type, not treat them like notifications.

## Event rendering model

Do not build the chat timeline as “just messages.” Build it as a **heterogeneous event feed**.

At minimum support these item/event classes:

* `userMessage`
* `agentMessage`
* `reasoning`
* `plan`
* `commandExecution`
* `fileChange`
* `mcpToolCall`
* `dynamicToolCall`
* `webSearch`
* `imageView`
* `enteredReviewMode` ([OpenAI Developers][1])

Recommended rendering:

* user / assistant messages as normal chat bubbles
* reasoning summaries as collapsible system blocks
* tool calls as expandable cards
* command execution showing cwd, argv, status, output preview
* file changes showing touched files + diff preview
* plan updates as progress checklist
* diff updates as a single aggregate diff panel for the active turn

Important nuance:
The docs say `turn/plan/updated` and `turn/diff/updated` can include empty `items` arrays and that `item/*` notifications are the source of truth for turn items. Build your state reducer accordingly. ([OpenAI Developers][1])

## Reasoning / verbosity configuration

For your “show all tool calls and reasoning traces, max out verbosity” requirement:

* set high model reasoning effort where supported
* request detailed reasoning summaries
* set high model verbosity
* render `reasoning` items and summaries in the UI ([OpenAI Developers][3])

Relevant config keys in Codex docs:

* `model_reasoning_effort = "high"` or possibly `xhigh` where supported
* `model_reasoning_summary = "detailed"`
* `model_verbosity = "high"` ([OpenAI Developers][3])

At turn level, the app-server examples show:

* `effort`
* `summary`
* model/personality overrides on `turn/start` ([OpenAI Developers][1])

I would do both:

* set sane defaults in local Codex config for dev
* still pass explicit per-thread / per-turn overrides from the Android client so behavior is deterministic

## Approval flow plan

This part matters most.

The app-server sends approvals as **server-initiated JSON-RPC requests**. Your client must render a modal/sheet and reply with one of the supported decision payloads. Command execution and file change approvals are separate flows. ([OpenAI Developers][1])

### Command approvals

Flow:

1. `item/started` with pending `commandExecution`
2. `item/commandExecution/requestApproval`
3. client sends decision
4. `serverRequest/resolved`
5. `item/completed` with final status ([OpenAI Developers][1])

Decisions supported:

* `accept`
* `acceptForSession`
* `decline`
* `cancel`
* `acceptWithExecpolicyAmendment` for command policy amendments ([OpenAI Developers][1])

Also handle:

* `networkApprovalContext` separately in UI; it is not just a generic shell prompt
* experimental `additionalPermissions` if you enable experimental API capability ([OpenAI Developers][1])

### File change approvals

Flow:

1. `item/started` with `fileChange`
2. `item/fileChange/requestApproval`
3. client sends decision
4. `serverRequest/resolved`
5. `item/completed` ([OpenAI Developers][1])

Decisions:

* `accept`
* `acceptForSession`
* `decline`
* `cancel` ([OpenAI Developers][1])

### UX recommendation

Use a bottom sheet with:

* prompt type: command / network / file changes
* scoped thread/turn metadata
* exact command or file set
* once / session / decline / cancel buttons
* explicit warning when full access mode is enabled

## Sandbox / permissions plan

Your requirement is:

* default to local sandbox
* allow toggle to full permissions
* Codex should only read approved project folders
* no worktrees for now

The closest fit is:

### Default mode

Use `workspaceWrite` sandbox with:

* `cwd = selected project path`
* `writableRoots = [selected project path]`
* restricted read access if you want to avoid broad host reads
* network access off by default unless you need it for that project/session ([OpenAI Developers][1])

The app-server docs explicitly support:

* `readOnly`
* `workspaceWrite`
* `dangerFullAccess`
* `externalSandbox` for pre-sandboxed environments ([OpenAI Developers][1])

For your use case, use:

* `workspaceWrite` as default
* `dangerFullAccess` only behind a UI toggle
* no `externalSandbox` unless you later sandbox the server process yourself

Also note:

* `configRequirements/read` lets the client inspect admin-enforced requirements for allowed approval policies and allowed sandbox modes. That’s useful if you later ship this in a managed environment. ([OpenAI Developers][1])

### Important design caveat

Your statement “Codex will only be allowed to read from these folders” is stricter than Codex’s basic default behavior. The app-server supports restricted read access on `sandboxPolicy`, including explicit `readableRoots`. If you really want strict read isolation, do not rely only on `cwd`; pass restricted read access in the sandbox policy too. ([OpenAI Developers][1])

### Full access toggle

Implement it as a thread/session-level control, not a hidden global switch.

Behavior:

* existing thread continues with its current effective mode unless user explicitly changes it
* new turns can pass a different `sandboxPolicy`
* show a strong banner when `dangerFullAccess` is active

## Local dev / test setup

Your requested setup is reasonable, but one Android detail matters:

* on the Android emulator, host-machine `localhost` is reached via `10.0.2.2`, not `127.0.0.1`
* on device or emulator, `adb reverse tcp:<port> tcp:<port>` lets the app use `localhost:<port>` against the host machine service ([Android Developers][4])

So for local testing:

1. dev starts `codex app-server --listen ws://127.0.0.1:4500` on the machine running Android Studio ([OpenAI Developers][1])
2. Android app connects using either:

   * `ws://10.0.2.2:4500` on emulator, or
   * `adb reverse` so the app can use `ws://localhost:4500` ([Android Developers][4])

For the “run the codex app server right from the android project but gitignore it” part:

* keep a local-only launch script / Gradle task / IDE run config in a gitignored path
* do not bake server launching into production app logic
* treat it as a dev convenience only

Also, because `ws://` is cleartext, Android debug builds may need network security allowances depending on target config. If you later move to TLS or a custom CA, Android’s network security config is the right place to manage trust. ([Android Developers][5])

## What I would tell the dev to implement first

### Phase 1: protocol skeleton

* add `CodexWsClient`
* connect / reconnect / handshake
* request-response correlation
* notification routing
* server-request routing for approvals

Done when:

* app can connect and successfully call `model/list`, `thread/list`, `thread/start` ([OpenAI Developers][1])

### Phase 2: project + threads index

* app-owned project registry
* project detail page with `thread/list(cwd=...)`
* open existing thread
* create new thread

Done when:

* selecting a project shows only its threads
* new thread lands in the right project bucket ([OpenAI Developers][1])

### Phase 3: thread screen + streaming

* `thread/read(includeTurns=true)`
* `thread/resume`
* `turn/start`
* live reducer for `turn/*` and `item/*`
* interrupt support via `turn/interrupt` ([OpenAI Developers][1])

Done when:

* prompts stream live
* tool calls and reasoning appear incrementally

### Phase 4: approvals

* command approval sheet
* file change approval sheet
* decision response handling
* `serverRequest/resolved` cleanup

Done when:

* app can approve once / session and the turn continues correctly ([OpenAI Developers][1])

### Phase 5: sandbox modes

* default `workspaceWrite`
* toggle to `dangerFullAccess`
* per-project cwd and writable roots
* optional restricted readable roots
* visual state indicator

Done when:

* thread startup and turn startup both honor the selected mode ([OpenAI Developers][1])

### Phase 6: polish

* aggregate diff view from `turn/diff/updated`
* plan progress from `turn/plan/updated`
* pagination for threads
* archive/unarchive
* error surfaces and reconnect UX ([OpenAI Developers][1])

## Non-obvious implementation notes

1. **Use generated schemas if possible.**
   The app-server can generate TypeScript or JSON Schema artifacts matching the exact installed Codex version. Even though your Android app is Kotlin, this is still useful as a version-locked protocol reference for your dev. ([OpenAI Developers][1])

2. **Pin the Codex version in dev docs.**
   `codex app-server` is marked experimental and may change without notice. Keep the Android feature tested against a known CLI version. ([OpenAI Developers][2])

3. **Do not overfit to the examples.**
   The docs mix config-level naming and protocol-level naming in places. Use the generated schemas / protocol source as the contract when implementation details matter. The protocol source is open in `openai/codex`. ([OpenAI Developers][6])

4. **Treat overloaded and disconnect cases as normal.**
   WebSocket mode uses bounded queues and can reject ingress with `-32001`. Build retries and UI recovery from day one. ([OpenAI Developers][1])

5. **No worktrees is fine.**
   Nothing in this plan depends on worktree support. Project path + thread filtering by `cwd` is enough for your MVP. ([OpenAI Developers][1])

## Suggested exact implementation method

For the senior dev, I’d frame it like this:

* Build a **Codex transport layer** around WebSocket JSON-RPC.
* Build a **state reducer** driven by app-server notifications, not ad hoc message parsing.
* Keep **project management app-owned**.
* Use **Codex threads as persisted conversation state**.
* Pass **`cwd` + sandbox policy on thread/turn start** so project scoping is explicit.
* Handle **approvals as inbound RPC calls** and answer them synchronously through the same socket.
* Render **reasoning + tools as structured timeline items**, not debug text.

That is the cleanest way to get a stable Android integration without re-implementing Codex semantics yourself.

## Best links for the dev

Start here:

* Codex app-server docs: ([OpenAI Developers][1])
* Config reference: ([OpenAI Developers][7])
* Sandbox / approvals overview: ([OpenAI Developers][8])
* CLI command reference for `codex app-server`: ([OpenAI Developers][2])
* Open-source app-server README: ([GitHub][9])
* Protocol source in GitHub: ([GitHub][10])
* Android emulator localhost / host mapping: ([Android Developers][4])

[1]: https://developers.openai.com/codex/app-server/ "Codex App Server"
[2]: https://developers.openai.com/codex/cli/reference/ "Command line options"
[3]: https://developers.openai.com/codex/config-basic/ "Config basics"
[4]: https://developer.android.com/studio/run/emulator-networking?utm_source=chatgpt.com "Set up Android Emulator networking | Android Studio"
[5]: https://developer.android.com/privacy-and-security/security-config?utm_source=chatgpt.com "Network security configuration"
[6]: https://developers.openai.com/codex/app-server/?utm_source=chatgpt.com "Codex App Server"
[7]: https://developers.openai.com/codex/config-reference/ "Configuration Reference"
[8]: https://developers.openai.com/codex/agent-approvals-security "Agent approvals & security"
[9]: https://github.com/openai/codex/blob/main/codex-rs%2Fapp-server%2FREADME.md?utm_source=chatgpt.com "codex/codex-rs/app-server/README.md at main"
[10]: https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2.rs?utm_source=chatgpt.com "codex/codex-rs/app-server-protocol/src/protocol/v2.rs at main"

