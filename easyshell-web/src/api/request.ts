import axios from 'axios';

/**
 * Access tokens live in sessionStorage, never in localStorage:
 * - localStorage survives browser restarts and is shared by every tab, so a single XSS
 *   yields a token that stays valid for its whole lifetime;
 * - the refresh token is never stored in the browser at all (the server rotates and
 *   revokes it server-side), so a stolen access token cannot be extended indefinitely.
 */
const TOKEN_KEY = 'token';

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearSession(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  // Defensive: older builds stored these keys, make sure they are gone as well.
  localStorage.removeItem('token');
  localStorage.removeItem('refreshToken');
}

/**
 * Removes internal details (file paths, class names, stack fragments) from server error
 * messages before they are rendered, without changing the user-facing wording.
 */
export function sanitizeErrorMessage(message: unknown): string {
  if (typeof message !== 'string' || message.trim().length === 0) {
    return '';
  }
  let text = message.trim();
  text = text.replace(/\b[\w.$]+(?:Exception|Error)\b.*$/g, '').trim();
  text = text.replace(/(?:\/[\w.$-]+){2,}/g, '[path]').trim();
  text = text.replace(/\b[a-z][\w$]*(?:\.[a-z][\w$]*){3,}\b/g, '[internal]').trim();
  text = text.replace(/\s+at\s+[\w$.]+\(.*\)$/g, '').trim();
  return text;
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

request.interceptors.request.use(
  (config) => {
    const token = getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

/**
 * Auth endpoints (login/refresh) must never trigger the session-expiry redirect:
 * they authenticate the request itself, so a 401/403/429 coming back from them is a
 * business outcome the login page renders as a toast. Redirecting would reload the
 * page and throw that toast (and the filled-in form) away.
 */
function isAuthRequest(url: unknown): boolean {
  return typeof url === 'string' && url.includes('/auth/');
}

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status;
    const body = error.response?.data;
    // Business failures arrive as a proper 4xx status together with the API envelope
    // ({code, message, data}). Re-surface them as a normal response so callers can
    // keep showing res.message, instead of every error collapsing into a generic
    // "operation failed".
    const hasEnvelope =
      typeof body === 'object' && body !== null && (body.code !== undefined || body.data !== undefined);
    if (status && status >= 400 && status < 500 && hasEnvelope) {
      return Promise.resolve(body);
    }
    // A 401 without an envelope is not a business decision: it comes from the security
    // filter chain (expired/missing token), i.e. the session really is dead. Only then
    // drop the token and go back to the login screen.
    if (status === 401 && !hasEnvelope && !isAuthRequest(error.config?.url)) {
      clearSession();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default request;
