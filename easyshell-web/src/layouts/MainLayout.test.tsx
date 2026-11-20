import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import MainLayout from './MainLayout';
import { getMe } from '../api/auth';

const session = vi.hoisted(() => ({ epoch: 0, token: 'present' as string | null, navigate: vi.fn(), listeners: new Set<() => void>(), unmount: vi.fn() }));
vi.mock('../api/request', () => ({
  getToken: () => session.token,
  clearSession: vi.fn(),
  getSessionEpoch: () => session.epoch,
  subscribeSessionEpoch: (listener: () => void) => { session.listeners.add(listener); return () => { session.listeners.delete(listener); }; },
}));
vi.mock('../api/auth', () => ({ getMe: vi.fn() }));
vi.mock('react-router-dom', () => ({ useNavigate: () => session.navigate, useLocation: () => ({ pathname: '/' }), Link: ({ children }: { children: React.ReactNode }) => <>{children}</>, Outlet: () => <TestOutlet /> }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en-US' } }) }));
vi.mock('../contexts/themeContext', () => ({ useTheme: () => ({ isDark: false, toggleTheme: vi.fn(), resetTheme: vi.fn() }) }));
vi.mock('../hooks/useResponsive', () => ({ useResponsive: () => ({ isMobile: false }) }));
vi.mock('../hooks/useVersion', () => ({ useVersion: () => undefined }));

function TestOutlet() {
  useEffect(() => () => session.unmount(), []);
  return <div>private outlet</div>;
}

function changeSession(token: string | null = 'present') {
  session.token = token;
  session.epoch++;
  session.listeners.forEach((listener) => listener());
}

function deferred() {
  let resolve!: (value: never) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<never>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

Object.defineProperty(window, 'matchMedia', { writable: true, value: () => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() }) });

beforeEach(() => { session.epoch = 0; session.token = 'present'; vi.resetAllMocks(); });
afterEach(cleanup);

describe('MainLayout session epoch', () => {
  it('isolates and unmounts the old session until the new session is verified', async () => {
    const next = deferred();
    vi.mocked(getMe).mockResolvedValueOnce({ code: 200, data: { username: 'old-user', role: 'admin' } } as never).mockReturnValueOnce(next.promise);
    render(<MainLayout />);
    expect(await screen.findByText('old-user')).toBeTruthy();
    expect(screen.getByText('private outlet')).toBeTruthy();
    act(() => changeSession());
    expect(screen.queryByText('old-user')).toBeNull();
    expect(screen.queryByText('private outlet')).toBeNull();
    expect(session.unmount).toHaveBeenCalledOnce();
    expect(getMe).toHaveBeenCalledTimes(2);
    await act(async () => next.resolve({ code: 200, data: { username: 'new-user', role: 'user' } } as never));
    expect(screen.getByText('new-user')).toBeTruthy();
    expect(screen.getByText('private outlet')).toBeTruthy();
  });

  it('ignores stale responses and rejections across rapid session changes', async () => {
    const old = deferred();
    const middle = deferred();
    const current = deferred();
    vi.mocked(getMe).mockReturnValueOnce(old.promise).mockReturnValueOnce(middle.promise).mockReturnValueOnce(current.promise);
    render(<MainLayout />);
    act(() => changeSession());
    act(() => changeSession());
    await act(async () => current.resolve({ code: 200, data: { username: 'current-user', role: 'user' } } as never));
    await act(async () => { old.resolve({ code: 200, data: { username: 'old-user', role: 'admin' } } as never); middle.reject(new Error('stale')); });
    expect(screen.getByText('current-user')).toBeTruthy();
    expect(screen.queryByText('old-user')).toBeNull();
    expect(session.navigate).not.toHaveBeenCalled();
  });

  it('stays isolated when verification fails', async () => {
    vi.mocked(getMe).mockResolvedValueOnce({ code: 200, data: { username: 'old-user', role: 'admin' } } as never).mockResolvedValueOnce({ code: 403 } as never);
    render(<MainLayout />);
    await screen.findByText('old-user');
    await act(async () => changeSession());
    expect(screen.queryByText('old-user')).toBeNull();
    expect(screen.queryByText('private outlet')).toBeNull();
  });

  it('stays isolated when the current verification rejects', async () => {
    vi.mocked(getMe).mockRejectedValue(new Error('network error'));
    await act(async () => { render(<MainLayout />); });
    expect(screen.queryByText('private outlet')).toBeNull();
    expect(session.navigate).not.toHaveBeenCalled();
  });

  it('redirects immediately when initially missing a token', () => {
    session.token = null;
    render(<MainLayout />);
    expect(session.navigate).toHaveBeenCalledWith('/login');
    expect(getMe).not.toHaveBeenCalled();
    expect(screen.queryByText('private outlet')).toBeNull();
  });

  it('redirects without a token and unmounts the old outlet', async () => {
    vi.mocked(getMe).mockResolvedValue({ code: 200, data: { username: 'old-user', role: 'admin' } } as never);
    render(<MainLayout />);
    await screen.findByText('old-user');
    act(() => changeSession(null));
    expect(screen.queryByText('private outlet')).toBeNull();
    expect(session.unmount).toHaveBeenCalledOnce();
    expect(session.navigate).toHaveBeenCalledWith('/login');
    expect(getMe).toHaveBeenCalledTimes(1);
  });
});
