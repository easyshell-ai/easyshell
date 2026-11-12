import axios from 'axios';

/**
 * Access tokens live in sessionStorage, never in localStorage:
 * - localStorage survives browser restarts and is shared by every tab, so a single XSS
 *   yields a token that stays valid for its whole lifetime;
 * - the refresh token is never stored in the browser at all (the server rotates and
 *   revokes it server-side), so a stolen access token cannot be extended indefinitely.
 */
const TOKEN_KEY = 'token';
let sessionEpoch = 0;
const sessionListeners = new Set<() => void>();

/** Opaque session generation; compare snapshots, never credentials. */
export function getSessionEpoch(): number {
  return sessionEpoch;
}

export function subscribeSessionEpoch(listener: () => void): () => void {
  sessionListeners.add(listener);
  return () => { sessionListeners.delete(listener); };
}

function advanceSessionEpoch(): void {
  sessionEpoch += 1;
  for (const listener of [...sessionListeners]) listener();
}

// sessionStorage events are delivered to other documents in the same tab, not
// the document that performed the write. Do not inspect or retain event values.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.storageArea === sessionStorage && (event.key === TOKEN_KEY || event.key === null)) {
      advanceSessionEpoch();
    }
  });
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
  advanceSessionEpoch();
}

export function clearSession(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  advanceSessionEpoch();
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

const requestEpochs = new WeakMap<object, number>();

request.interceptors.request.use(
  (config) => {
    requestEpochs.set(config, getSessionEpoch());
    const token = getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => { throw error; },
  { synchronous: true }
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

function isStaleRequest(config: object | undefined): boolean {
  return !!config && requestEpochs.has(config) && requestEpochs.get(config) !== getSessionEpoch();
}

function staleSessionError(): Error {
  // Do not attach the response or request: either may contain sensitive data.
  return new Error('Request belongs to an expired session');
}

request.interceptors.response.use(
  (response) => {
    if (isStaleRequest(response.config)) throw staleSessionError();
    return response.data;
  },
  (error) => {
    if (isStaleRequest(error.config)) return Promise.reject(staleSessionError());
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
    if (status === 401 && !hasEnvelope && !isAuthRequest(error.config?.url)
      && requestEpochs.get(error.config) === getSessionEpoch()) {
      clearSession();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default request;
