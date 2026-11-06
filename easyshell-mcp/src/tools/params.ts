import { z } from "zod";

/**
 * EasyShell IDs are server generated and look like "ag_xxx", "task_xxx", "job_xxx",
 * "tag_xxx", "cluster_xxx" or a small integer.
 *
 * Validating them here is a security control, not cosmetic tidiness: tool parameters are
 * interpolated into request paths and {@code new URL(path, base)} normalises "..", so an
 * unchecked value such as "../../../api/v1/user/list" turns one tool into a request for a
 * completely different endpoint.
 */
const ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

export function idParam(description: string): z.ZodString {
  return z
    .string()
    .regex(ID_PATTERN, "must be 1-64 characters of letters, digits, '-' or '_'")
    .describe(description);
}

/** Numeric identifiers must be integers inside a realistic database id range. */
export function idNumberParam(description: string): z.ZodNumber {
  return z
    .number()
    .int("must be an integer")
    .min(1)
    .max(2_147_483_647)
    .describe(description);
}

export function pageParam(): z.ZodOptional<z.ZodDefault<z.ZodNumber>> {
  return z.number().int().min(0).max(100_000).default(0).optional();
}

export function sizeParam(defaultSize = 20, maxSize = 200): z.ZodOptional<z.ZodDefault<z.ZodNumber>> {
  return z
    .number()
    .int()
    .min(1)
    .max(maxSize)
    .default(defaultSize)
    .optional();
}

export function timeoutSecondsParam(): z.ZodOptional<z.ZodDefault<z.ZodNumber>> {
  return z
    .number()
    .int()
    .min(1)
    .max(86_400)
    .default(600)
    .optional();
}

export function isSafeId(value: string): boolean {
  return ID_PATTERN.test(value);
}
