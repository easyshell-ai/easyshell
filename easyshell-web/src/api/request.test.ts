import { describe, it, expect, beforeEach, beforeAll } from 'vitest';
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

describe('response interceptor', () => {
  beforeAll(() => {
    // jsdom cannot navigate; replace location so the redirect is observable.
    Object.defineProperty(window, 'location', {
      value: { href: LOGIN_URL },
      writable: true,
      configurable: true,
    });
  });

  beforeEach(() => {
    window.location.href = LOGIN_URL;
    sessionStorage.clear();
    localStorage.clear();
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
