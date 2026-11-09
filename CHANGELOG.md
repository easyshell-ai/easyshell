# Changelog

All notable changes to EasyShell are documented here. Versions follow
`MAJOR.MINOR.PATCH`; the running version is shown in the bottom-left corner of the
web UI and is served by `GET /api/v1/system/version`.

## [Unreleased]

## [1.2.0] - 2026-11-09

Feature release: the workflow engine (orchestrate operations as a versioned,
visual DAG) and a full frontend visual refresh built on a design-token layer.
Two security defects that were reachable in the released 1.1.2 code are also
fixed here. No migration steps are required — see the upgrade notes in the
release description.

### Added
- **Server — the workflow execution engine.** A new `workflow` package runs a
  workflow definition as a persisted DAG: topological scheduling in waves,
  end-of-wave checkpointing, per-run and per-node concurrency limits, retry with
  exponential backoff and jitter, idempotency via unique keys on node runs and
  conditional status transitions, cancellation that propagates to the next
  checkpoint, and a watchdog plus a wake-up scanner for suspended and queued
  runs. `ai/orchestrator` is untouched, so AI Chat keeps its exact behaviour.
- **Server — five node types.** `script-exec` (dispatches to a real Task through
  the same chokepoint as any other script, inheriting audit, live logs, the
  timeout watchdog and agent-disconnect handling), `condition` (expression
  evaluated into `output.result`; the edge condition is the only gate),
  `notify` (pushes through the existing bot channels), `approval` (suspends the
  run without holding a thread, with timeout, optional mandatory comment, an
  immutable detail snapshot, and a `human-confirm` configuration variant) and
  `wait` (a sleep-loop with a cancellation checkpoint every 500 ms).
- **Server — parameter passing between nodes.** Run inputs, node outputs and
  `outputVar` aliases, with a hand-written fail-closed expression evaluator
  (no SpEL/OGNL), JsonPath read-only path resolution, four typed values
  (string / number / boolean / json), and a four-layer defence for script
  interpolation: a whitelist value policy, single-quoted reference rendering,
  a segment-level substitution guard, and the existing risk gate at the
  dispatch chokepoint. Values are visible only to a node's topological
  successors; anything that looks like a slot but does not match the contract
  is refused outright.
- **Server — failure policies.** `abort` / `skip` / `partial`, resolved per edge,
  per node and per definition, with the strictest policy winning when incoming
  edges disagree and unknown policies treated as `abort`.
- **Server — 17 REST endpoints** under `/api/v1/workflow` for definitions,
  versions, drafts, validation, publishing, triggering, runs, cancellation,
  single-node test runs and approvals, plus a WebSocket run channel at
  `/ws/workflow/run/{runId}`. Method-level `@PreAuthorize` decides the role;
  three data-level checks in one place decide ownership, and every
  path-variable endpoint has a parameterised non-owner-403 test. Outbound VOs
  parse JSON columns and keep internal fields off the wire; inputs and
  variables pass through the sensitive-data filter on the way out.
- **Server — Flyway for the five new workflow tables.** One migration script
  (`V1_2_0__workflow_engine.sql`) creates `workflow`, `workflow_version`,
  `workflow_run`, `workflow_node_run` and `workflow_approval`. The 22
  pre-existing tables stay under Hibernate's `ddl-auto`: a schema filter keeps
  the two jurisdictions from overlapping, and a startup checker compares every
  column, nullability, length, key and unique constraint of the new tables
  against the JPA entities and refuses to boot on a mismatch. On an existing
  1.1.x database Flyway baselines first (`baseline-on-migrate: true`), so no
  manual step is needed; `validate-on-migrate` fails fast if an applied script
  is edited.
- **Web — the workflow editor with a fully interactive canvas.** Box selection,
  alignment guides with snapping, snap-to-grid, undo/redo, minimap, auto layout
  (reusing the depth algorithm from the AI plan view — no layout library),
  copy/paste/duplicate with key regeneration, and the full keyboard map. The
  canvas is the only editor of structure and the form is the only editor of a
  selected node's configuration; the node palette can only add. Invalid
  connections (self-loops, duplicates, cycles, a condition on a `condition`
  node's false branch) are refused client-side with the server authoritative.
- **Web — workflow list, editor, run list, run monitor, approval centre and
  trigger dialog**, including the optimistic-lock 409 dialog (overwrite, save a
  copy, or discard — never an automatic merge) and the read-only run canvas
  coloured by what actually happened. All new UI strings ship in both locales.
- **Web — a design-token layer applied across every route.** `src/theme/` is the
  single source for colour, type and spacing; AntD's `ConfigProvider` is fed
  from it and the same values are exposed as `--es-*` CSS variables so business
  semantics and component styling cannot drift apart. Visual baselines for the
  core routes were regenerated in the same commit, as the regression policy
  requires.
- **Web — a Playwright visual regression suite** with dark/light projects at two
  viewports, axe-core accessibility checks, API fixtures shaped after the real
  envelopes, and a canvas drag benchmark. Updating a baseline must happen in the
  same commit as the style change.

### Changed
- **Web — visual refresh.** Colour, type, spacing and table density across all
  routes, driven by the token layer. Dark is the default for an operations
  console; light is one toggle away. No layout dimensions changed.
- **Server — runs now record the agent version at dispatch time**
  (`workflow_node_run.agent_versions`). Dispatching to an older agent still
  executes normally, so upgrading agents is recommended but not required.
- **Version identifiers** moved to 1.2.0: `Dockerfile.server` ARG/ENV,
  `docker-compose.build.yml` `VERSION` defaults, `application.yml`
  `easyshell.version`, `VersionController` default, the AI `User-Agent` string,
  and `easyshell-mcp` package version. `easyshell-web/package.json` stays at the
  `0.0.0` placeholder.

### Fixed
- **Server — the dispatch risk gate could be switched off from the request body.**
  1.1.2 moved the risk check into `TaskServiceImpl.createAndDispatch` and added a
  settable `riskCleared` boolean to the task DTO to avoid mis-blocking
  already-authorised paths. A Jackson binding test proves that posting
  `{"riskCleared":true}` alongside a non-LOW script dispatched the job and wrote
  the row: any caller could bypass the chokepoint with one JSON key. The field is
  gone. Dispatch is now parameterised by a `DispatchAuthority` enum plus an
  evidence reference decided by the server from the call path, never bound from
  the request: `HUMAN_APPROVED` (evidence is the approved task row),
  `BUILTIN_READONLY_SCRIPT` (evidence is that the script hashes to the known
  detection script) and `USER_SCHEDULED_TASK` (evidence is that the scheduled
  task row exists and belongs to the caller). BANNED commands can only ever be
  waived by a human approval; when evidence cannot be produced the dispatch is
  refused. Every waived dispatch is logged synchronously with the authority,
  evidence reference, risk class and matched rules, but never the script body.
  An architecture test pins the list of classes allowed to reference
  `DispatchAuthority` and asserts the request DTO can never grow an exemption
  field again.
- **Server — read-only downgrading commands that write.** The 1.1.1 read-only
  downgrade was keyed on the command name alone, and several commands hide write
  operations behind a read-only face: `find ... -delete` was LOW (auto-approved
  destructive deletion) while the identical command wrapped in `bash -c` was
  HIGH. Write forms are now excluded from the read-only allowlist — `find` with
  `-delete` / `-exec` / `-execdir` / `-ok` / `-okdir` / `-fprint` / `-fprintf`,
  `awk` with `system()` / `getline` / print-to-file / pipe, `sort -o`, `date -s`,
  `ping -f`, the mutating `ip` and `ifconfig` subcommands, `env FOO=1 <cmd>`,
  and `hostname <newname>` — each narrowed in both the direct and the `bash -c`
  paths. Read-only usage is untouched and `bash -c "df -h"` is pinned by a
  regression test. Bare redirection (`cmd > /tmp/x`) remains a known blind spot,
  documented and scheduled for a later release.
- **Server — twelve workflow API contract mismatches between the two ends.** The
  front end was written against the design document while the back end was
  written against the same document read separately; the seams only showed up
  once both were running. Nine were fixed on the server and three on the client:
  the detail endpoint now returns the workflow, its versions and its draft
  together; version summaries and definitions are flattened rather than nested;
  saving a draft returns the base-version token alongside the definition; run,
  node-run and approval responses go through outbound VOs; approvals carry the
  workflow they belong to; the page endpoint accepts keyword and category; and
  `currentVersion` is back-filled for lists. Three server defects that only an
  end-to-end run could expose are fixed here as well: the approval decision
  response echoed a stale snapshot (a `@Modifying` update does not refresh the
  persistence context), queued runs were never pumped back because the scheduled
  thread had no transaction, and an idempotency key reused with different inputs
  returned the first run instead of a conflict. On the client, the draft is read
  from the detail response, validation consumes `{valid, issues}` instead of a
  bare array, and triggering sends `{input, idempotencyKey}` in the body.
- **CI — the visual regression job now runs.** The preview server is started
  directly instead of through the npm wrapper (whose failure produced no usable
  diagnostics), `fonts-noto-cjk` is installed before the browser (the baselines
  were rendered with it and the stack never named a CJK family, so every Chinese
  string fell back to a different face), the CJK family is pinned explicitly,
  the visual port moved off Vite's default with a hard failure on collision, the
  canvas frame budget is a ratio rather than a fixed additive allowance, and the
  workflow can be re-run manually.

### Security
- The dispatch risk gate can no longer be disabled by a request body field; the
  authority model is a server-side enum with verifiable evidence, an
  architecture test locking its callers, and synchronous audit for every waived
  dispatch. See *Fixed* above for the full description.
- Commands that write behind a read-only face are no longer auto-approved.
- The workflow run WebSocket channel verifies run ownership during the
  handshake and denies on missing runs and on lookup failures alike, following
  the pattern 1.1.2 established for the task-log channel; 449 bodies and
  approval risk fields carry the risk level and the matched pattern but never
  the command text.
- **No secret channel.** There is no `secret` variable type and no way to inject
  a credential into a script: the agent protocol carries only the script body,
  so a secret injected here would be written into the task's script column and
  printed by any `env`-like command. Outbound messages, node output, error and
  warning messages and run variables all pass through the existing
  sensitive-data filter (mask `***`), reusing the one implementation rather than
  adding a second.

### Documentation
- New "Workflow Engine" section in `README.md` and `README.zh-CN.md`, with
  matching sections in the ja / ko / ru / zh-TW translations: what the engine
  is, the five node types, the three data channels and their real boundaries,
  the failure policies, approvals and the human in the loop, the full canvas
  interaction set, monitoring and audit, and an explicit list of what is not
  implemented.
- New "Workflow from 0 to 1" guide on the documentation site (English and
  简体中文): bring the stack up, compose a three-node workflow with the real
  definition JSON, pass values between nodes, choose failure policies, run an
  approval, monitor a run, and the questions people actually ask. Every command
  and payload is checked against the server's contract test.
- `AGENTS.md` now records the workflow API hard rules, the local stack and test
  commands, and the PR checklist (design admission criteria that belong to
  engineering discipline).

## [1.1.2] - 2026-11-08

Security patch release. Three pre-existing authorization/auditability defects were
verified against the 1.1.1 code base and fixed at their root cause, with regression
tests that fail on the old code. No migration steps are required.

### Security
- **Server — WebSocket channels authenticated but did not authorize.** The task-log
  channel (`/ws/task/log/{taskId}`) accepted any handshake with a valid user token
  and never checked who owned the task, so any logged-in user could subscribe to
  the live job logs of any other user's task (script output included). The
  handshake now performs a resource-level ownership check — task owner or an
  administrator role, anything else is closed with a policy-violation status and a
  warning log; unknown task ids and lookup failures are denied (fail-closed), so
  the channel no longer confirms whether a task id exists.
- **Server — the terminal channel had the same gap.** `/ws/terminal/{agentId}`
  opened an interactive host shell for any authenticated user. Hosts are shared
  infrastructure without an owner field, so the channel is now gated by RBAC:
  only `operator`, `admin` and `super_admin` may open a shell, `viewer` and
  unknown/missing roles are rejected.
- **Server — task list and page endpoints leaked every user's tasks.**
  `GET /task/list` and `GET /task/page` returned all tasks regardless of owner,
  including script content; the detail endpoint was already owner-checked. Both
  list endpoints now filter by `createdBy` for non-administrators, matching the
  existing detail/agent-jobs semantics (administrators still see everything).
- **Server — the AI task tool attributed work to a phantom user.** The AI tool
  that creates tasks hardcoded `userId = 1`, so every AI-created task was audited
  as "user 1"; the AI script-library tool did the same. Both now take the acting
  user from the session context (the same mechanism the script-execution tool
  already used) and refuse to create anything when no user context is present.
  The AI task tool's read paths (list/detail) are now scoped to the calling user
  as well.
- **Server — the command risk gate moved to the dispatch chokepoint.**
  `TaskServiceImpl.createAndDispatch` is the single path every script execution
  goes through, but it performed no risk check of its own: the check lived only
  in the task controller, and the scheduled-inspection and AI-tool callers
  bypassed it entirely. The (post-substitution) script is now assessed by
  `CommandRiskEngine` inside `createAndDispatch` and anything above LOW is
  rejected with 449 before any row is written. Paths that were authorized
  upstream (a human-approved task, a user-created scheduled inspection, the
  built-in read-only detection script) carry an explicit `riskCleared` flag;
  every other caller — including future ones — is gated by default.
  Controller-level behaviour is unchanged (LOW scripts still dispatch
  immediately, and the second assessment of an already-approved script is
  idempotent).

### Documentation
- `SECURITY.md` records the known limitation that job output produced by a
  user's own script (for example a command that prints environment variables) is
  stored and streamed back to that task's owner and administrators; secrets
  should not be placed in scripts.

## [1.1.1] - 2026-11-07

### Fixed
- **Server — task counters could stall or undercount.** `updateTaskStatus` was a
  read-modify-write over the job list: when several agents reported at the same
  moment both threads read the same stale counts and the last write won, so a task
  could lose results and stay `running` forever. Counting is now a single atomic
  `UPDATE ... success_count = success_count + 1` guarded by the job's own
  conditional finalisation, so every report is applied exactly once.
- **Server — concurrent job log lines were lost.** `appendJobLog` read the output
  column, concatenated and saved it back with no transaction, so parallel log
  lines overwrote each other. Appending is now one atomic `UPDATE` with tail
  truncation (MEDIUMTEXT can no longer be overflowed) inside a transaction.
- **Server — dispatching to an agent that dropped between the check and the send
  left the task stuck.** The post-commit dispatch callback ran `@Modifying`
  repository calls with no transaction left, which Hibernate rejected
  (`no transaction is in progress`) while `save()` silently dropped its changes.
  Status updates moved to `TaskCounterService`, which offers a `REQUIRES_NEW`
  entry point for the post-commit path.
- **Server — the risk engine demanded approval for everyday read-only work.**
  `bash -c "df -h"`, `crontab -l` and similar read-only interpreter commands were
  classified HIGH, so every routine inspection needed manual confirmation. Such
  commands are now downgraded to LOW only when every sub-command is on a
  read-only allowlist; anything containing write redirections, `rm -rf`,
  `curl | sh`, `chmod 777`, `mkfs`, `dd`, `useradd`, `systemctl stop`,
  `kubectl delete` and 40+ other dangerous patterns still fails closed to HIGH.
  Command splitting is now quote-aware, so `grep -E "a|b" file` is no longer
  mis-parsed.
- **Web — the task log stream never connected.** The 1.1.0 WebSocket
  authentication required a token, but the task page opened its socket without
  one, so the handshake was rejected and real-time logs were completely broken
  (only the 2s polling refresh survived). The token is now sent as a query
  parameter, matching the terminal page.
- **Web — a failed login reloaded the login page.** The response interceptor
  treated every 401 as an expired session and redirected to `/login` — including
  the 401 produced by a wrong password, which reloaded the page, discarded the
  error toast and cleared the form. Only a 401 without an API envelope (i.e. from
  the security filter chain) now ends the session.
- **Web — a stale `index.html` broke every route after a deploy** (root cause of
  issue #5). `nginx.conf` had no cache headers, so browsers kept an entry HTML
  referencing content-hash chunks that no longer existed. The entry document is
  now `no-store`, hashed assets are `immutable` for a year.
- **Agent — 16 exited terminals permanently blocked new ones.** A shell that
  exited on its own was closed but never removed from the manager, so dead
  sessions kept counting against `MaxSessions` until the WebSocket reconnected.
  Exited sessions now unregister themselves.
- **Agent — the ping goroutine leaked on every connection flap.** When the read
  loop ended, `c.conn` was set to nil and the heartbeat goroutine spun on an empty
  ticker until shutdown instead of exiting. The loop now stops when its read loop
  finishes.

### Added
- Web test baseline (vitest + Testing Library, 11 tests) covering the two
  regressions above: task-log socket URL building and the 401 interceptor.
- Server regression tests for the atomic counters and log appends (8 threads,
  real H2) and 51 risk-classification cases.
- Agent regression tests for terminal session release and the ping loop.
- **Agent build baseline**: `go build` / `go vet` / `go test` now pass locally
  (the component previously had no verified build in this environment).

### Documentation
- Added a "MCP Server & AI Skills" section to `README.md` and `README.zh-CN.md`
  (plus the corresponding sections in the ja / ko / ru / zh-TW translations): what
  `easyshell-mcp` is, stdio and HTTP configuration examples, the 20-tool surface in
  3 tiers, and how to install the `easyshell-skill` definition.
- Documented the current boundary explicitly: the MCP server is an outbound bridge
  (EasyShell called by an AI client); consuming external MCP servers inside the
  platform is planned, not implemented — see issue #6.
- Rewrote `SECURITY.md`: supported-versions table (1.1.x supported, 1.0.x critical
  fixes only), private reporting via GitHub Security Advisory instead of a personal
  email, a 90-day coordinated disclosure window, an MCP-server security section and
  a summary of the 1.1.0 hardening work.
- Added GitHub issue templates: `bug_report.yml` (version, deployment method,
  component, reproduction, expected vs actual, redaction reminders, environment),
  `feature_request.yml` and `config.yml` (contact links to Discord / discussions).

## [1.1.0] - 2026-11-07

Security hardening release. A full security scan of the server, web console, MCP
server and agent was carried out and its findings were remediated; no functional
regressions remain.

### Security
- Ran a full security scan across all four components (black-box against a running
  deployment plus white-box source review) and remediated the findings.
- Authentication and authorization: WebSocket and agent endpoints now require
  credentials, method-level authorization is enforced, and resource ownership is
  verified.
- Secret management: no hardcoded fallback secrets remain, secrets are generated at
  deployment time, and refresh tokens are rotated, replay-detected and revocable.
- Transport and headers: restrictive security headers, CORS and origin allowlists,
  DNS-rebinding protection, mandatory webhook signature verification.
- Input validation: identifier format checks, path traversal and symlink defences,
  SSRF redirect validation, bounded request sizes and pagination.
- Data protection: authenticated encryption for stored credentials, minimal
  environments for spawned shells, sanitised error messages.
- Added regression tests for the authentication, risk-classification and
  path-validation layers.

### Deployment
- Zero-configuration Docker deployment: `docker compose up -d` generates the
  database password, JWT secret, encryption key and agent registration secret on
  first boot and reuses them on every start. No `.env` file is required.
- `scripts/deploy.sh` waits for the health check and prints the sign-in details.
- MySQL and Redis are bound to loopback only.

## [1.0.0]

Initial release: host management, script library, batch execution with streaming
logs, Web SSH terminal with file manager, AI assistant and orchestration, scheduled
inspections, audit logging and role-based access control.
