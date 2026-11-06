export interface EasyShellConfig {
  url: string;
  username: string;
  password: string;
}

const DEFAULT_URL = "http://localhost:18080";
const DEFAULT_USER = "easyshell";

function isLoopback(hostname: string): boolean {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "[::1]" ||
    hostname.endsWith(".localhost")
  );
}

function normalizeUrl(rawUrl: string): string {
  const value = rawUrl.trim();
  if (!value.startsWith("http://") && !value.startsWith("https://")) {
    throw new Error("EASYSHELL_URL must start with http:// or https://");
  }

  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("EASYSHELL_URL is not a valid URL");
  }

  // Plain HTTP sends the operator's password in clear text; only allow it for loopback.
  if (parsed.protocol === "http:" && !isLoopback(parsed.hostname)) {
    throw new Error(
      "EASYSHELL_URL must use https:// for non-local servers (plain http would leak the password)",
    );
  }

  return value.replace(/\/+$/, "");
}

export function loadConfig(): EasyShellConfig {
  const rawUrl = process.env.EASYSHELL_URL?.trim() || DEFAULT_URL;
  const rawUsername = process.env.EASYSHELL_USER?.trim() || DEFAULT_USER;
  const rawPassword = process.env.EASYSHELL_PASS?.trim();

  if (!rawPassword) {
    throw new Error("EASYSHELL_PASS is required");
  }

  return {
    url: normalizeUrl(rawUrl),
    username: rawUsername,
    password: rawPassword,
  };
}
