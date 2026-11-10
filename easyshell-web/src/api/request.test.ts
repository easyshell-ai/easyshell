import { describe, it, expect, beforeAll, afterEach } from 'vitest';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import request from './request';

/**
 * Regression tests for the response interceptor. After the 1.1.0 hardening the server
 * answers with real 401/403/429 statuses, and the login/refresh endpoints share this
 * request instance: a 401 there used to be treated as an expired session, which
 * reloaded the login page and threw away the error toast (and the filled-in form).
 */

const LOGIN_URL = 'http://localhost/';

function rejectAdapter(status: number, data: unknown): AxiosAdapter {
  // Mimics what the built-in axios adapters do for a non-2xx status.
  return (config: InternalAxiosRequestConfig) => {
    const error = Object.assign(new Error(`Request failed with status code ${status}`), {
      config,
      isAxiosError: true,
      response: { status, data, headers: {}, config },
    });
    return Promise.reject(error);
  };
}

/** What GlobalExceptionHandler returns: HTTP 4xx with the R envelope in the body. */
const envelope = (code: number, message: string) => ({ code, message, data: null });

/**
 * jsdom cannot navigate, so `location` has to be stubbed for the redirect to be observable.
 * How the stub has to be written depends on the pool, and that is the whole bug:
 *
 * - `threads`/`forks` (the default) populate the worker's *own* global, so `window.location`
 *   is a configurable accessor vitest installed on the Node global. Replacing it with
 *   `Object.defineProperty(window, 'location', { value: { href } })` works.
 * - `vmThreads`/`vmForks` run the test inside jsdom's own VM context, where `window` IS that
 *   global and `location` is jsdom's `[LegacyUnforgeable]` property — non-configurable, and
 *   every member of the `Location` wrapper is `[Unforgeable]` too. So neither
 *   `Object.defineProperty(window, 'location', ...)` nor stubbing `href` on the instance
 *   (nor `vi.stubGlobal`, which defines on the very same object) works: they all throw
 *   `TypeError: Cannot redefine property: location`. Thrown from `beforeAll`, that fails the
 *   whole suite and vitest reports every test as *skipped* rather than failed.
 *
 * What works everywhere is patching the `href` accessor on the jsdom `LocationImpl`
 * prototype, which is a plain configurable property. The wrapper's own unforgeable
 * accessor delegates to it, so `window.location.href` reads and writes our stub. The impl is
 * reached through `window._document._location` — jsdom's own handle, stable across the
 * jsdom versions this repo pins.
 */
function stubLocationHref(): void {
  const impl = (window as unknown as { _document: { _location: object } })._document?._location;
  if (!impl) {
    throw new Error('cannot reach the jsdom Location implementation to stub location.href');
  }
  const proto = Object.getPrototypeOf(impl) as { href: string };
  let href = LOGIN_URL;
  Object.defineProperty(proto, 'href', {
    configurable: true,
    get: () => href,
    set: (next: string) => {
      href = next;
    },
  });
}

describe('response interceptor', () => {
  beforeAll(() => {
    stubLocationHref();
  });

  afterEach(() => {
    window.location.href = LOGIN_URL;
  });

  it('resolves a login 401 envelope instead of treating it as an expired session', async () => {
    const res = await request.post('/v1/auth/login', {}, {
      adapter: rejectAdapter(401, envelope(401, 'Invalid username or password')),
    });
    expect(res).toEqual(envelope(401, 'Invalid username or password'));
    expect(window.location.href).toBe(LOGIN_URL);
    expect(sessionStorage.getItem('token')).toBeNull();
  });

  it('resolves login 403 (disabled account) and 429 (rate limit) envelopes without redirecting', async () => {
    for (const status of [403, 429]) {
      window.location.href = LOGIN_URL;
      const res = await request.post('/v1/auth/login', {}, {
        adapter: rejectAdapter(status, envelope(status, 'rejected')),
      });
      expect(res).toEqual(envelope(status, 'rejected'));
      expect(window.location.href).toBe(LOGIN_URL);
    }
  });

  it('never redirects an envelope-less 401 on an auth endpoint either', async () => {
    await expect(
      request.post('/v1/auth/refresh', {}, { adapter: rejectAdapter(401, '') })
    ).rejects.toThrow();
    expect(window.location.href).toBe(LOGIN_URL);
  });

  it('clears the session and redirects to /login when a regular endpoint returns an envelope-less 401', async () => {
    sessionStorage.setItem('token', 'expired-access-token');
    await expect(
      request.get('/v1/task/list', { adapter: rejectAdapter(401, '') })
    ).rejects.toThrow();
    expect(window.location.href).toBe('/login');
    expect(sessionStorage.getItem('token')).toBeNull();
  });

  it('still surfaces business 4xx envelopes of regular endpoints as resolved responses', async () => {
    const res = await request.get('/v1/task/list', {
      adapter: rejectAdapter(403, envelope(403, 'Access denied')),
    });
    expect(res).toEqual(envelope(403, 'Access denied'));
    expect(window.location.href).toBe(LOGIN_URL);
  });

  it('passes successful responses through as the unwrapped body', async () => {
    const body = { code: 200, message: 'success', data: [{ id: 'task-1' }] };
    const res = await request.get('/v1/task/list', {
      adapter: (config: InternalAxiosRequestConfig) =>
        Promise.resolve({ data: body, status: 200, statusText: 'OK', headers: {}, config }),
    });
    expect(res).toEqual(body);
  });
});
