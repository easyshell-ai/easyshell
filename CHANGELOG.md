# Changelog

All notable changes to EasyShell are documented here. Versions follow
`MAJOR.MINOR.PATCH`; the running version is shown in the bottom-left corner of the
web UI and is served by `GET /api/v1/system/version`.

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
