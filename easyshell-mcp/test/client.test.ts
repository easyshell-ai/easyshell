import { describe, expect, it } from "vitest";
import { EasyShellClient } from "../src/client.js";

const config = {
  url: "http://localhost:18080",
  username: "easyshell",
  password: "not-a-real-password",
};

describe("EasyShellClient path handling", () => {
  const client = new EasyShellClient(config);

  it("rejects traversal segments in request paths", async () => {
    // new URL("/api/v1/host/../../../api/v1/user/list", base) resolves to /api/v1/user/list,
    // so the client refuses anything that is not a plain /api/... path.
    await expect(client.get("/api/v1/host/../../../api/v1/user/list")).rejects.toMatchObject({
      code: "INVALID_PATH",
    });
    await expect(client.get("/etc/passwd")).rejects.toMatchObject({ code: "INVALID_PATH" });
  });

  it("rejects backslash based traversal", async () => {
    await expect(client.get("/api/v1/host/..\\..\\v3/api-docs")).rejects.toMatchObject({
      code: "INVALID_PATH",
    });
  });
});

describe("normalizeUrl (via loadConfig)", () => {
  it("requires https for remote servers", async () => {
    const { loadConfig } = await import("../src/config.js");
    const previous = { ...process.env };
    try {
      process.env.EASYSHELL_URL = "http://easyshell.example.com";
      process.env.EASYSHELL_USER = "easyshell";
      process.env.EASYSHELL_PASS = "x";
      expect(() => loadConfig()).toThrow(/https/i);
    } finally {
      process.env = previous;
    }
  });

  it("allows http for loopback", async () => {
    const { loadConfig } = await import("../src/config.js");
    const previous = { ...process.env };
    try {
      process.env.EASYSHELL_URL = "http://localhost:18080/";
      process.env.EASYSHELL_USER = "easyshell";
      process.env.EASYSHELL_PASS = "x";
      expect(loadConfig().url).toBe("http://localhost:18080");
    } finally {
      process.env = previous;
    }
  });
});
