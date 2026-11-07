# Changelog

All notable changes to EasyShell are documented here. Versions follow
`MAJOR.MINOR.PATCH`; the running version is shown in the bottom-left corner of the
web UI and is served by `GET /api/v1/system/version`.

## [Unreleased]

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
