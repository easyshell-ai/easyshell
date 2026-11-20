import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sendChatStream } from './ai';

const session = vi.hoisted(() => ({ epoch: 0, listeners: new Set<() => void>() }));
vi.mock('./request', () => ({
  default: {}, getToken: () => null, clearSession: vi.fn(),
  getSessionEpoch: () => session.epoch,
  subscribeSessionEpoch: (listener: () => void) => {
    session.listeners.add(listener);
    return () => { session.listeners.delete(listener); };
  },
}));
vi.mock('../i18n', () => ({ default: { language: 'en-US' } }));

const start = (error = vi.fn(), complete = vi.fn()) =>
  sendChatStream({} as Parameters<typeof sendChatStream>[0], vi.fn(), error, complete);
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

beforeEach(() => { vi.useFakeTimers(); session.epoch = 0; session.listeners.clear(); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('chat stream POST failures', () => {
  it.each([
    ['network rejection', () => Promise.reject(new Error('network'))],
    ['HTTP 500', () => Promise.resolve({ ok: false, status: 500, statusText: 'Server Error' })],
    ['missing body', () => Promise.resolve({ ok: true, body: null })],
    ['stream read failure', () => Promise.resolve({ ok: true, body: { getReader: () => ({ read: () => Promise.reject(new Error('read failed')) }) } })],
  ])('reports %s once without resending', async (_label, response) => {
    const fetchMock = vi.fn(response);
    vi.stubGlobal('fetch', fetchMock);
    const error = vi.fn(), complete = vi.fn();
    start(error, complete);
    await flush();
    await vi.advanceTimersByTimeAsync(200_000);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(error).toHaveBeenCalledOnce();
    expect(error.mock.calls[0][0]).toBeInstanceOf(Error);
    expect(complete).not.toHaveBeenCalled();
    expect(session.listeners.size).toBe(0);
  });

  it('reports a header timeout distinctly without resending', async () => {
    const fetchMock = vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal!.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    vi.stubGlobal('fetch', fetchMock);
    const error = vi.fn(), complete = vi.fn();
    start(error, complete);
    await vi.advanceTimersByTimeAsync(200_000);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(error).toHaveBeenCalledOnce();
    expect(error.mock.calls[0][0]).toMatchObject({ name: 'TimeoutError', message: 'Stream timeout' });
    expect(complete).not.toHaveBeenCalled();
    expect(session.listeners.size).toBe(0);
  });

  it.each(['user', 'session'])('%s cancellation stays silent and does not resend', async (kind) => {
    const fetchMock = vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal!.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    vi.stubGlobal('fetch', fetchMock);
    const error = vi.fn(), complete = vi.fn();
    const controller = start(error, complete);
    if (kind === 'user') controller.abort();
    else { session.epoch++; for (const listener of [...session.listeners]) listener(); }
    await vi.advanceTimersByTimeAsync(200_000);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(error).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
    expect(session.listeners.size).toBe(0);
  });
});
