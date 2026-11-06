import { describe, expect, it } from "vitest";
import { EasyShellError, mapHttpError, toMcpError } from "../src/errors.js";

describe("mapHttpError", () => {
  it("keeps the HTTP status in statusCode and a machine readable code in code", () => {
    const error = mapHttpError(449, { message: "需要人工审批" });

    expect(error).toBeInstanceOf(EasyShellError);
    expect(error.statusCode).toBe(449);
    expect(error.code).toBe("APPROVAL_REQUIRED");
  });

  it("maps common statuses", () => {
    expect(mapHttpError(401, {}).code).toBe("AUTH_EXPIRED");
    expect(mapHttpError(403, {}).code).toBe("FORBIDDEN");
    expect(mapHttpError(404, {}).code).toBe("NOT_FOUND");
    expect(mapHttpError(503, {}).code).toBe("SERVER_ERROR");
  });
});

describe("toMcpError", () => {
  it("flags approval errors as errors", () => {
    const result = toMcpError(mapHttpError(449, { message: "需要人工审批" }));

    expect(result.isError).toBe(true);
    expect(result.content[0]?.text).toContain("APPROVAL_REQUIRED");
  });

  it("never leaks a raw stack trace", () => {
    const result = toMcpError(new Error("boom"));

    expect(result.isError).toBe(true);
    expect(result.content[0]?.text).toBe("boom");
  });
});
