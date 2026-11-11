import { describe, it, expect, beforeAll, afterEach, vi } from 'vitest';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import request, { clearSession, getSessionEpoch, setToken, subscribeSessionEpoch } from './request';

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

describe('session epoch', () => {
  afterEach(() => clearSession());

  it('notifies on set, clear, and switching back, and supports unsubscribe', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeSessionEpoch(listener);
    const initial = getSessionEpoch();
    setToken('a');
    setToken('b');
    setToken('a');
    clearSession();
    expect(getSessionEpoch()).toBe(initial + 4);
    expect(listener).toHaveBeenCalledTimes(4);
    expect(listener).toHaveBeenCalledWith();
    unsubscribe();
    setToken('c');
    expect(listener).toHaveBeenCalledTimes(4);
  });

  it('responds to relevant cross-document storage events without exposing their values', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeSessionEpoch(listener);
    const initial = getSessionEpoch();
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', storageArea: sessionStorage }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'token', storageArea: localStorage }));
    expect(getSessionEpoch()).toBe(initial);
    window.dispatchEvent(new StorageEvent('storage', { key: 'token', storageArea: sessionStorage, newValue: 'a' }));
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: sessionStorage }));
    expect(getSessionEpoch()).toBe(initial + 2);
    expect(listener).toHaveBeenCalledTimes(2);
    expect(listener).toHaveBeenCalledWith();
    unsubscribe();
  });
});

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

  it('does not clear a newer session when an old request returns 401 after switching back', async () => {
    setToken('a');
    let rejectRequest!: (reason: unknown) => void;
    let config!: InternalAxiosRequestConfig;
    let started!: () => void;
    const dispatched = new Promise<void>((resolve) => { started = resolve; });
    const pending = request.get('/v1/task/list', {
      adapter: (requestConfig) => {
        config = requestConfig;
        started();
        return new Promise((_resolve, reject) => { rejectRequest = reject; });
      },
    });
    await dispatched;
    setToken('b');
    setToken('a');
    rejectRequest(Object.assign(new Error('expired'), { config, response: { status: 401, data: '' } }));
    await expect(pending).rejects.toThrow('Request belongs to an expired session');
    expect(sessionStorage.getItem('token')).toBe('a');
    expect(window.location.href).toBe(LOGIN_URL);
    clearSession();
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

  it.each(['/v1/task/list', '/v1/auth/login'])('rejects a stale success from %s without exposing its body', async (url) => {
    let finish!: () => void;
    const pending = request.post(url, {}, {
      adapter: (config) => new Promise((resolve) => {
        finish = () => resolve({ data: { code: 200, data: { value: 'old' } }, status: 200, statusText: 'OK', headers: {}, config });
      }),
    });
    setToken('new-session');
    finish();
    await expect(pending).rejects.toThrow('Request belongs to an expired session');
    clearSession();
  });

  it('rejects a stale business envelope without exposing it or redirecting', async () => {
    let fail!: () => void;
    const pending = request.get('/v1/task/list', {
      adapter: (config) => new Promise((_resolve, reject) => {
        fail = () => reject(Object.assign(new Error('old failure'), {
          config, response: { status: 403, data: envelope(403, 'old response') },
        }));
      }),
    });
    setToken('new-session');
    fail();
    await expect(pending).rejects.toThrow('Request belongs to an expired session');
    expect(window.location.href).toBe(LOGIN_URL);
    clearSession();
  });

  it('does not deliver an older concurrent login after the first login establishes a session', async () => {
    const completions: Array<() => void> = [];
    const adapter: AxiosAdapter = (config) => new Promise((resolve) => {
      completions.push(() => resolve({ data: { code: 200, data: {} }, status: 200, statusText: 'OK', headers: {}, config }));
    });
    const older = request.post('/v1/auth/login', {}, { adapter });
    const newer = request.post('/v1/auth/login', {}, { adapter });
    completions[1]();
    await newer;
    setToken('new-session');
    completions[0]();
    await expect(older).rejects.toThrow('Request belongs to an expired session');
    clearSession();
  });
});
