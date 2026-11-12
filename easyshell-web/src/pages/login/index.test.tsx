import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import Login from './index';
import { login } from '../../api/auth';

const mocks = vi.hoisted(() => ({ epoch: 0, setToken: vi.fn(), navigate: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('../../api/auth', () => ({ login: vi.fn() }));
vi.mock('../../api/request', () => ({ getSessionEpoch: () => mocks.epoch, setToken: mocks.setToken }));
vi.mock('react-router-dom', () => ({ useNavigate: () => mocks.navigate }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('antd', async (importOriginal) => {
  const antd = await importOriginal<typeof import('antd')>();
  return { ...antd, message: { ...antd.message, success: mocks.success, error: mocks.error } };
});

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => { mocks.epoch = 0; vi.clearAllMocks(); });
afterEach(() => cleanup());

async function submit() {
  render(<Login />);
  fireEvent.change(screen.getByPlaceholderText('login.username'), { target: { value: 'user' } });
  fireEvent.change(screen.getByPlaceholderText('login.password'), { target: { value: 'password' } });
  fireEvent.click(screen.getByRole('button', { name: 'login.submit' }));
  await waitFor(() => expect(login).toHaveBeenCalledOnce());
}

describe('login session epoch', () => {
  it('commits a successful response for the current session', async () => {
    vi.mocked(login).mockResolvedValue({ code: 200, data: { accessToken: 'fixture' } } as never);
    await submit();
    await waitFor(() => expect(mocks.setToken).toHaveBeenCalledOnce());
    expect(mocks.success).toHaveBeenCalledOnce();
    expect(mocks.navigate).toHaveBeenCalledWith('/');
  });

  it('discards a response when the session changes after response delivery', async () => {
    let deliver!: (value: unknown) => void;
    const response = new Promise((resolve) => { deliver = resolve; });
    vi.mocked(login).mockImplementation(() => response.then((value) => {
      // Model a session transition after the request layer delivered the response,
      // before the awaiting login page resumes.
      mocks.epoch++;
      return value;
    }) as never);
    await submit();
    await act(async () => { deliver({ code: 200, data: { accessToken: 'fixture' } }); await response; });
    expect(mocks.setToken).not.toHaveBeenCalled();
    expect(mocks.success).not.toHaveBeenCalled();
    expect(mocks.error).not.toHaveBeenCalled();
    expect(mocks.navigate).not.toHaveBeenCalled();
  });

  it('does not report an error from an expired session', async () => {
    let reject!: (reason: Error) => void;
    vi.mocked(login).mockReturnValue(new Promise((_resolve, rejectPromise) => { reject = rejectPromise; }) as never);
    await submit();
    await act(async () => { mocks.epoch++; reject(new Error('expired')); });
    expect(mocks.error).not.toHaveBeenCalled();
  });
});
