import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { generateScript } from './script';
import { sendChatStream } from './ai';

const session = vi.hoisted(() => ({ epoch: 0, clear: vi.fn(), listeners: new Set<() => void>() }));
vi.mock('./request', () => ({
  default: {},
  getToken: () => null,
  getSessionEpoch: () => session.epoch,
  clearSession: session.clear,
  subscribeSessionEpoch: (listener: () => void) => {
    session.listeners.add(listener);
    return () => { session.listeners.delete(listener); };
  },
}));
vi.mock('../i18n', () => ({ default: { language: 'en-US' } }));

const starts = [
  { name: 'script', start: (...[event, error, complete]: Parameters<typeof generateScript> extends [unknown, ...infer Callbacks] ? Callbacks : never) =>
    generateScript({ prompt: '', os: '', scriptType: '', language: '' }, event, error, complete) },
  { name: 'chat', start: (...[event, error, complete]: Parameters<typeof sendChatStream> extends [unknown, ...infer Callbacks] ? Callbacks : never) =>
    sendChatStream({} as Parameters<typeof sendChatStream>[0], event, error, complete) },
];

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

beforeEach(() => {
  session.epoch = 0;
  session.clear.mockReset();
  session.listeners.clear();
  vi.stubGlobal('window', { location: { href: '/' } });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe.each(starts)('$name stream session isolation', ({ start }) => {
  it('aborts a request still waiting for response headers', async () => {
    vi.useFakeTimers();
    const response = deferred<Response>();
    const fetchMock = vi.fn<(_input: RequestInfo | URL, _init?: RequestInit) => Promise<Response>>()
      .mockReturnValue(response.promise);
    vi.stubGlobal('fetch', fetchMock);
    const event = vi.fn(), error = vi.fn(), complete = vi.fn();
    const controller = start(event, error, complete);
    session.epoch++;
    for (const listener of [...session.listeners]) listener();
    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    expect(controller.signal.aborted).toBe(false);
    expect(session.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    response.resolve({ ok: false, status: 401 } as Response);
    await flush();
    expect(session.clear).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });

  it('actively cancels an idle reader on session change and cleans up', async () => {
    const pending = deferred<ReadableStreamReadResult<Uint8Array>>();
    const cancel = vi.fn(() => { pending.resolve({ done: true, value: undefined }); return Promise.resolve(); });
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({ read: () => pending.promise, cancel }) } });
    vi.stubGlobal('fetch', fetchMock);
    const event = vi.fn(), error = vi.fn(), complete = vi.fn();
    const controller = start(event, error, complete);
    await flush();
    session.epoch++;
    for (const listener of [...session.listeners]) listener();
    await flush();
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    expect(controller.signal.aborted).toBe(false);
    expect(cancel).toHaveBeenCalledOnce();
    expect(session.listeners.size).toBe(0);
    expect(event).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });

  it('cleans up subscriptions on completion and external abort', async () => {
    const cancel = vi.fn().mockResolvedValue(undefined);
    const pending = deferred<ReadableStreamReadResult<Uint8Array>>();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({ read: () => pending.promise, cancel }) } }));
    const controller = start(vi.fn(), vi.fn(), vi.fn());
    await flush();
    controller.abort();
    expect(controller.signal.aborted).toBe(true);
    expect(cancel).toHaveBeenCalledOnce();
    expect(session.listeners.size).toBe(0);
  });
  it('ignores a stale 401 without clearing or redirecting the new session', async () => {
    const response = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn(() => response.promise));
    const event = vi.fn(), error = vi.fn(), complete = vi.fn();
    start(event, error, complete);
    session.epoch++;
    response.resolve({ ok: false, status: 401 } as Response);
    await flush();
    expect(session.clear).not.toHaveBeenCalled();
    expect(window.location.href).toBe('/');
    expect(event).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });

  it('preserves current-session 401 handling', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    start(vi.fn(), vi.fn(), vi.fn());
    await flush();
    expect(session.clear).toHaveBeenCalledOnce();
    expect(window.location.href).toBe('/login');
  });

  it('drops late stream events and completion after a session change', async () => {
    const read = deferred<ReadableStreamReadResult<Uint8Array>>();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({ read: () => read.promise }) } }));
    const event = vi.fn(), error = vi.fn(), complete = vi.fn();
    start(event, error, complete);
    await flush();
    session.epoch++;
    read.resolve({ done: false, value: new TextEncoder().encode('data: {"type":"DONE"}\n') });
    await flush();
    expect(event).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });

  it('delivers current-session events and completion', async () => {
    const read = vi.fn().mockResolvedValueOnce({ done: false, value: new TextEncoder().encode('data: {"type":"DONE"}\n') });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({ read }) } }));
    const event = vi.fn(), error = vi.fn(), complete = vi.fn();
    start(event, error, complete);
    await flush();
    expect(event).toHaveBeenCalledWith({ type: 'DONE' });
    expect(complete).toHaveBeenCalledOnce();
    expect(error).not.toHaveBeenCalled();
    expect(session.listeners.size).toBe(0);
  });
});

it('cleans up after a chat network failure without scheduling a retry', async () => {
  vi.useFakeTimers();
  const fetchMock = vi.fn().mockRejectedValue(new Error('network'));
  vi.stubGlobal('fetch', fetchMock);
  const event = vi.fn(), error = vi.fn(), complete = vi.fn();
  sendChatStream({} as Parameters<typeof sendChatStream>[0], event, error, complete);
  await flush();
  expect(error).toHaveBeenCalledOnce();
  expect(error.mock.calls[0][0]).toBeInstanceOf(Error);
  expect(event).not.toHaveBeenCalled();
  expect(complete).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(0);
  expect(session.listeners.size).toBe(0);
  session.epoch++;
  for (const listener of [...session.listeners]) listener();
  expect(vi.getTimerCount()).toBe(0);
  expect(session.listeners.size).toBe(0);
  await vi.advanceTimersByTimeAsync(2000);
  expect(fetchMock).toHaveBeenCalledOnce();
});

it('ignores a late chat network failure and does not resend under a different session', async () => {
  vi.useFakeTimers();
  const response = deferred<Response>();
  const fetchMock = vi.fn(() => response.promise);
  vi.stubGlobal('fetch', fetchMock);
  const event = vi.fn(), error = vi.fn(), complete = vi.fn();
  sendChatStream({} as Parameters<typeof sendChatStream>[0], event, error, complete);
  await flush();
  session.epoch++;
  response.reject(new Error('network'));
  await flush();
  await vi.advanceTimersByTimeAsync(1000);
  expect(fetchMock).toHaveBeenCalledOnce();
  expect(event).not.toHaveBeenCalled();
  expect(error).not.toHaveBeenCalled();
  expect(complete).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(0);
  expect(session.listeners.size).toBe(0);
});
