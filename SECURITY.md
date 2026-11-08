# Security Policy

## Supported Versions

Security fixes are released for the current minor series and the one immediately
before it. Upgrade to a supported version before reporting a bug that may already
be fixed.

| Version | Supported          | Notes |
|---------|--------------------|-------|
| 1.1.x   | :white_check_mark: | Current series — security fixes are backported here |
| 1.0.x   | :warning:          | Critical fixes only, until 1.2.0 ships |
| < 1.0   | :x:                | No longer supported — please upgrade |

The running version is shown in the bottom-left corner of the web UI and is served
by `GET /api/v1/system/version`.

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

Report privately through GitHub Security Advisories — the report is visible to
maintainers only until it is published:

**[https://github.com/easyshell-ai/easyshell/security/advisories/new](https://github.com/easyshell-ai/easyshell/security/advisories/new)**

> Prefer not to use GitHub? Open a **private** discussion thread on our
> [Discord](https://discord.gg/akQqRgNB6t) and ask a maintainer for a private
> channel. Do not post vulnerability details in public channels.

- **Title**: `[SECURITY] EasyShell - Brief description`
- **Language**: English or 简体中文

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected version(s) (see *Supported Versions* above)
- Affected component (`easyshell-server` / `easyshell-web` / `easyshell-agent` / `easyshell-mcp`)
- Potential impact
- Suggested fix (if any)

### Response Timeline

| Stage | Timeframe |
|-------|-----------|
| Acknowledgment | Within 48 hours |
| Initial assessment | Within 7 days |
| Fix release (critical) | Within 14 days |
| Fix release (non-critical) | Next scheduled release |

### Coordinated Disclosure

We follow coordinated disclosure with a **90-day disclosure window**: a
vulnerability may be published once a fix is available, or 90 days after the
report, whichever comes first. Reporters get an acknowledgment in the advisory and
in the release notes (unless they prefer to remain anonymous). If you plan to
publish earlier, please tell us first so we can warn users.

## Security Hardening in 1.1.0

Release 1.1.0 was a dedicated security-hardening release: a full scan of the
server, web console, MCP server and agent (black-box against a running deployment
plus white-box source review) was carried out and its findings remediated, with no
functional regressions. Summary of what was addressed:

- **Authentication & authorization** — WebSocket and agent endpoints now require
  credentials, method-level authorization is enforced, and resource ownership is
  verified.
- **Secret management** — no hardcoded fallback secrets remain, secrets are
  generated at deployment time, and refresh tokens are rotated, replay-detected
  and revocable.
- **Transport & headers** — restrictive security headers, CORS and origin
  allowlists, DNS-rebinding protection, mandatory webhook signature verification.
- **Input validation** — identifier format checks, path traversal and symlink
  defences, SSRF redirect validation, bounded request sizes and pagination.
- **Data protection** — authenticated encryption for stored credentials, minimal
  environments for spawned shells, sanitised error messages.
- **Regression tests** added for the authentication, risk-classification and
  path-validation layers.

See [`CHANGELOG.md`](./CHANGELOG.md) for the full 1.1.0 notes.

## Security Hardening in 1.1.2

Release 1.1.2 is a security patch on top of 1.1.1. Three pre-existing defects
were verified and fixed; no functional behaviour changed. Summary:

- **WebSocket authorization** — the task-log and terminal WebSocket channels
  authenticated the handshake but did not authorize the resource: any logged-in
  user could subscribe to another user's task logs or open an interactive shell
  on any host. The task-log channel now verifies task ownership (owner or
  administrator, fail-closed on unknown tasks and lookup failures), and the
  terminal channel is restricted to the `operator`/`admin`/`super_admin` roles.
- **Task list authorization** — `GET /task/list` and `GET /task/page` returned
  every user's tasks (script content included) to any authenticated user; they
  now filter by owner for non-administrators, matching the detail endpoint.
- **Auditability of AI-created work** — the AI task and script tools attributed
  created records to a hardcoded user id; they now use the acting user from the
  session context and refuse to act without one.
- **Risk gate at the dispatch chokepoint** — the command risk assessment now
  runs inside `TaskServiceImpl.createAndDispatch` (the single dispatch path) in
  addition to the controller, so scheduled inspections, AI tools and any future
  caller cannot bypass it. Upstream-authorized paths (human-approved tasks,
  user-created scheduled tasks, the built-in detection script) carry an explicit
  approval flag.

### Known limitation (1.1.2)

Job output is stored and streamed verbatim to the subscribers of that task: if a
script you run prints secrets (for example `env`, `printenv` or reading a
credentials file), the output is persisted in the job record and pushed over the
task-log WebSocket to the task owner and administrators. Do not place secrets in
scripts; treat script output as sensitive data and restrict who can create tasks
accordingly. Redacting arbitrary output server-side is not feasible without
corrupting legitimate results, so this is tracked as a known limitation rather
than a silent filter.

## Security Architecture

### Authentication & Access Control

- Session-based authentication with Spring Security
- Role-based access control (Admin / User)
- All API endpoints require authentication (except login)

### Script Execution Safeguards

- **Risk assessment engine**: All scripts are evaluated before execution
  - `LOW` — Auto-execute allowed
  - `MEDIUM` — Requires user confirmation
  - `HIGH` — Requires admin approval
  - `BANNED` — Blocked, requires admin override
- **Approval workflow**: High-risk operations go through a review queue
- **Audit logging**: All operations (login, script execution, AI interactions) are recorded

### AI Security

- AI-generated scripts are subject to the same risk assessment as manual scripts
- AI cannot bypass risk controls or approval workflows
- System prompts enforce safety rules and prevent privilege escalation

### Agent Communication

- Agents connect to the server via WebSocket
- Agent registration requires a valid server address and port
- Script execution is sandboxed to the agent's host OS user permissions

### MCP Server (`easyshell-mcp`)

- The MCP server authenticates to the EasyShell API with `EASYSHELL_USER` /
  `EASYSHELL_PASS`; every tool call runs through the same risk assessment,
  approval workflow and audit logging as the web console.
- The generated client config file is written with `0600` permissions because it
  contains the EasyShell password.
- The HTTP transport refuses to bind a non-loopback address without a bearer token
  (`--token` / `MCP_AUTH_TOKEN`), validates the `Host` header against DNS
  rebinding, restricts CORS to explicit origins (`--allowed-origin` /
  `MCP_ALLOWED_ORIGINS`), and caps body size and concurrent sessions.
- Prefer `MCP_PASSWORD` over `--pass`: command-line arguments are visible in `ps`.

## Best Practices for Deployment

1. **Change default credentials** — Update `easyshell` / `easyshell@changeme` immediately after first login
2. **Use HTTPS** — Place a reverse proxy (Nginx, Caddy) with TLS in front of EasyShell
3. **Restrict network access** — Limit who can reach the EasyShell server port
4. **Review AI model configuration** — Ensure API keys are stored securely and not exposed
5. **Regular updates** — Keep EasyShell and its dependencies up to date
6. **Monitor audit logs** — Periodically review operation logs for suspicious activity

## Scope

The following are **in scope** for security reports:

- Authentication / authorization bypass
- Remote code execution outside of intended script execution
- SQL injection, XSS, CSRF
- Sensitive data exposure (credentials, API keys)
- AI prompt injection leading to unauthorized operations
- Agent impersonation or unauthorized registration
- MCP server transport, authentication or credential-handling flaws

The following are **out of scope**:

- Vulnerabilities in third-party dependencies (report to upstream)
- Denial of service via resource exhaustion (unless trivially exploitable)
- Issues requiring physical access to the server
- Social engineering

## Acknowledgments

We appreciate the security research community. Reporters of valid vulnerabilities
will be credited in release notes and in the published GitHub Security Advisory
(unless they prefer to remain anonymous).
