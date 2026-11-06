import type { EasyShellConfig } from "./config.js";
import { EasyShellError, mapHttpError } from "./errors.js";

interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
}

export class EasyShellClient {
  private readonly config: EasyShellConfig;
  private token: string | null = null;
  private refreshToken: string | null = null;

  public constructor(config: EasyShellConfig) {
    this.config = config;
  }

  public async get<T>(path: string, params?: Record<string, string>): Promise<T> {
    return this.request<T>("GET", path, undefined, params);
  }

  public async post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("POST", path, body);
  }

  public async put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("PUT", path, body);
  }

  public async delete<T>(path: string): Promise<T> {
    return this.request<T>("DELETE", path);
  }

  private async request<T>(
    method: "GET" | "POST" | "PUT" | "DELETE",
    path: string,
    body?: unknown,
    params?: Record<string, string>,
    retryOnUnauthorized = true,
  ): Promise<T> {
    await this.ensureAuthenticated();

    const url = new URL(path, this.config.url);
    if (params) {
      for (const [key, value] of Object.entries(params)) {
        url.searchParams.set(key, value);
      }
    }

    const headers = new Headers();
    headers.set("Authorization", `Bearer ${this.token}`);
    headers.set("Accept", "application/json");
    if (body !== undefined) {
      headers.set("Content-Type", "application/json");
    }

    let response: Response;
    try {
      response = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      throw new EasyShellError(
        error instanceof Error ? error.message : "Network request failed.",
        0,
        "NETWORK_ERROR",
      );
    }

    const responseBody = await this.parseJson(response);

    if (response.status === 401 && retryOnUnauthorized) {
      await this.refreshOrLogin();
      return this.request(method, path, body, params, false);
    }

    if (!response.ok) {
      throw mapHttpError(response.status, responseBody);
    }

    if (!this.isEnvelope(responseBody)) {
      throw new EasyShellError("Unexpected API response format.", response.status, "INVALID_RESPONSE");
    }

    if (responseBody.code !== 200) {
      throw new EasyShellError(responseBody.message || "EasyShell API returned an error.", response.status, "API_ERROR");
    }

    return responseBody.data as T;
  }

  private async ensureAuthenticated(): Promise<void> {
    if (this.token) {
      return;
    }
    await this.login();
  }

  private async login(): Promise<void> {
    const endpoint = new URL("/api/v1/auth/login", this.config.url);
    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({
          username: this.config.username,
          password: this.config.password,
        }),
      });
    } catch (error) {
      throw new EasyShellError(
        error instanceof Error ? error.message : "Authentication request failed.",
        0,
        "NETWORK_ERROR",
      );
    }

    const body = await this.parseJson(response);
    if (!response.ok) {
      throw mapHttpError(response.status, body);
    }

    if (!this.isEnvelope(body) || body.code !== 200 || !this.isLoginResponse(body.data)) {
      throw new EasyShellError("Invalid login response from EasyShell API.", response.status, "INVALID_AUTH_RESPONSE");
    }

    this.token = body.data.accessToken;
    this.refreshToken = body.data.refreshToken;
  }

  private async refreshOrLogin(): Promise<void> {
    if (!this.refreshToken) {
      this.token = null;
      await this.login();
      return;
    }

    const endpoint = new URL("/api/v1/auth/refresh", this.config.url);
    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({
          refreshToken: this.refreshToken,
        }),
      });
    } catch {
      this.token = null;
      await this.login();
      return;
    }

    const body = await this.parseJson(response);
    if (!response.ok || !this.isEnvelope(body) || body.code !== 200 || !this.isLoginResponse(body.data)) {
      this.token = null;
      await this.login();
      return;
    }

    this.token = body.data.accessToken;
    this.refreshToken = body.data.refreshToken;
  }

  private async parseJson(response: Response): Promise<unknown> {
    const text = await response.text();
    if (!text.trim()) {
      return {};
    }

    try {
      return JSON.parse(text) as unknown;
    } catch {
      return { message: text };
    }
  }

  private isEnvelope<T>(value: unknown): value is ApiEnvelope<T> {
    if (typeof value !== "object" || value === null) {
      return false;
    }

    const candidate = value as Partial<ApiEnvelope<T>>;
    return (
      typeof candidate.code === "number" &&
      typeof candidate.message === "string" &&
      Object.prototype.hasOwnProperty.call(candidate, "data")
    );
  }

  private isLoginResponse(value: unknown): value is LoginResponse {
    if (typeof value !== "object" || value === null) {
      return false;
    }

    const candidate = value as Partial<LoginResponse> & { token?: unknown };
    const accessToken =
      typeof candidate.accessToken === "string"
        ? candidate.accessToken
        : typeof candidate.token === "string"
          ? candidate.token
          : null;
    return accessToken !== null && typeof candidate.refreshToken === "string";
  }
}
