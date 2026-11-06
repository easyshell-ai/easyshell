import { describe, expect, it } from "vitest";
import {
  idNumberParam,
  idParam,
  isSafeId,
  pageParam,
  sizeParam,
  timeoutSecondsParam,
} from "../src/tools/params.js";

describe("idParam", () => {
  const schema = idParam("id");

  it("accepts server generated ids", () => {
    for (const value of ["ag_demo_host_01", "task_1a2b3c4d5e6f7890", "job_abc123", "Tag-1"]) {
      expect(schema.safeParse(value).success).toBe(true);
    }
  });

  it("rejects path traversal", () => {
    // This is the whole point: new URL() normalises ".." so the raw value would otherwise
    // reach a completely different endpoint.
    for (const value of [
      "../../../api/v1/user/list",
      "..",
      "a/b",
      "a\\b",
      "host?x=1",
      "host#frag",
      "host/../../v3/api-docs",
      "",
      "x".repeat(65),
    ]) {
      expect(schema.safeParse(value).success).toBe(false);
    }
  });
});

describe("idNumberParam", () => {
  const schema = idNumberParam("id");

  it("accepts safe integers", () => {
    expect(schema.safeParse(12).success).toBe(true);
  });

  it("rejects out of range and fractional values", () => {
    for (const value of [0, -1, 1.5, 3e9, Number.NaN]) {
      expect(schema.safeParse(value).success).toBe(false);
    }
  });
});

describe("pagination params", () => {
  it("bounds page and size", () => {
    expect(pageParam().safeParse(-1).success).toBe(false);
    expect(pageParam().safeParse(0).success).toBe(true);
    expect(sizeParam().safeParse(1e9).success).toBe(false);
    expect(sizeParam().safeParse(20).success).toBe(true);
  });

  it("bounds timeoutSeconds", () => {
    expect(timeoutSecondsParam().safeParse(0).success).toBe(false);
    expect(timeoutSecondsParam().safeParse(999_999).success).toBe(false);
    expect(timeoutSecondsParam().safeParse(600).success).toBe(true);
  });
});

describe("isSafeId", () => {
  it("matches the documented id format", () => {
    expect(isSafeId("ag_demo_host_01")).toBe(true);
    expect(isSafeId("../etc/passwd")).toBe(false);
  });
});
