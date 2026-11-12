# NON_HOST_V3 integration contract

This document and `NonHostApprovalResult` are preparatory only. They do not enable
V3, authorize any run, or change the output of existing approval nodes. Enable
the following changes together, not incrementally in production.

## Classification and access

* `WorkflowRunProvenanceService.initialize` is the only place that may assign
  `NON_HOST_V3`: in the transaction creating a new authenticated manual run,
  after checking the immutable version's identity, validity and SHA-256 digest.
  The whole graph, including disconnected nodes, must validate and consist only
  of the audited V1 data-only node types plus `approval`. Require at least one
  approval; leave approval-free V1 classification unchanged. Run input must be a
  JSON object and `startedBy` a positive authenticated user id. Trust in user
  input and definition defaults has the same limits as V1; it is not proof that
  a person did not copy host data into them.
* Repository `findComplete` must recognize V3 only with zero task associations;
  task association remains HOST-only. Do not migrate, infer, backfill, or upgrade
  existing HOST, PURE, V1, V2, missing, or damaged records. V2 remains denied.
* `canView` must independently verify the stored kind, run/version binding,
  digest, graph, input and empty task set, and fail closed on exceptions. It is
  an additional check, not a replacement for ownership or approval role checks.
  Restricted approval management remains unchanged: control does not confer
  visibility; approval details/comments are returned only under existing
  approval-admin and content-access policy, never as general run data.

## Mandatory execution boundary

* Before any V3 approval executes or resumes, obtain a positive, verified V3
  provenance decision bound to this exact run and immutable version. Missing,
  malformed, mismatched, unrecognized or unavailable provenance must fail the
  node closed; it must never silently fall back to legacy behavior. Conversely,
  verified legacy runs must retain their original comment behavior; absence of
  proof of V3 is not proof of a safe legacy run. A boolean `isV3` that conflates
  legacy with unknown is therefore insufficient. The engine and executor need a
  tri-state or an exception-based verified-kind API.
* For verified V3 only, use `NonHostApprovalResult.output(approval)` for approved
  and timeout-approved outputs and `NonHostApprovalResult.rejectionMessage()`
  for rejection errors. The stored approval comment remains subject to existing
  restricted approval-management policy. Never propagate it to node output,
  node error, event, warning, logs, or AI inputs. Audit all approval decision and
  attempt paths, including retries, timeout, restarts, and exception paths.
* V3 requires a definition and runtime guard rejecting references to approval
  `comment`, including `nodes.<key>.output.comment`, outputVar aliases, bracket
  notation and descendants. Cover conditions, edge expressions, template
  interpolation, named parameters, and every other evaluator entry point.
  Reject unsupported or ambiguous forms rather than guessing. Whole-object
  references must not provide a route to comments, including stale node outputs;
  validate persisted upstream state before it becomes readable. Do not impose a
  new V3-only restriction on historical runs. A definition guard by itself is
  not a runtime guard, and sanitizing only the final presenter is insufficient.

## Evidence required before enabling

Test repository damaged/unknown records, version and digest mismatch, missing
provenance, forbidden task association and transaction rollback; authenticated
manual creation versus scheduled/unauthenticated/historical runs; every node in
disconnected graphs; disallowed types and malformed definitions. Test approved,
rejected, timeout, retry and restart paths with a unique comment marker, checking
persisted node outputs/errors/events and captured AI requests. Test all supported
reference spellings, aliases, whole-object and stale-state bypasses at both
definition and runtime boundaries. Prove HOST/V1/V2 behavior and restricted
approval management do not broaden. Run the repository-mandated server suite
and full Spring wiring test after integration. None of this evidence is supplied
by the isolated result-builder test alone.
