import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue || key }) }));
vi.mock('../../api/ai', () => ({ getInspectReports: vi.fn(), getInspectReport: vi.fn() }));
vi.mock('../../components/MarkdownContent', () => ({ default: () => null }));
import AiReports from './reports';
import { getInspectReport, getInspectReports } from '../../api/ai';

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

describe('report detail failures', () => {
  const report = (id: number, taskName: string) => ({ id, taskName, taskType: 'inspect', status: 'SUCCESS', targetSummary: 'host', createdAt: '2026-01-01', scriptOutput: `secret ${id}` });
  const unavailable = 'Report details are unavailable. Please retry.';

  beforeEach(() => {
    vi.mocked(getInspectReports).mockResolvedValue({ code: 200, data: { content: [report(1, 'first'), report(2, 'second')], totalElements: 2 } } as never);
  });

  it.each(['envelope', 'network'])('keeps %s failure visible and retries the current id', async (kind) => {
    if (kind === 'envelope') vi.mocked(getInspectReport).mockResolvedValueOnce({ code: 403 } as never);
    else vi.mocked(getInspectReport).mockRejectedValueOnce(new Error('offline'));
    vi.mocked(getInspectReport).mockResolvedValueOnce({ code: 200, data: report(2, 'second') } as never);
    render(<AiReports />);
    const buttons = await screen.findAllByRole('button', { name: /reports.detail.viewDetail/ });
    fireEvent.click(buttons[1]);
    expect(await screen.findByText(unavailable)).toBeTruthy();
    expect(screen.getByText('reports.historyUnavailable')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('secret 2')).toBeTruthy();
    expect(getInspectReport).toHaveBeenNthCalledWith(2, 2);
    expect(screen.queryByText(unavailable)).toBeNull();
  });

  it('clears previous sensitive detail and ignores responses from switched or closed requests', async () => {
    let resolveSecond!: (value: never) => void;
    let resolveClosed!: (value: never) => void;
    vi.mocked(getInspectReport)
      .mockResolvedValueOnce({ code: 200, data: report(1, 'first') } as never)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }))
      .mockResolvedValueOnce({ code: 200, data: report(1, 'first') } as never)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveClosed = resolve; }));
    render(<AiReports />);
    const buttons = await screen.findAllByRole('button', { name: /reports.detail.viewDetail/ });
    fireEvent.click(buttons[0]);
    expect(await screen.findByText('secret 1')).toBeTruthy();
    fireEvent.click(buttons[1]);
    expect(screen.queryByText('secret 1')).toBeNull();
    fireEvent.click(buttons[0]);
    expect(await screen.findByText('secret 1')).toBeTruthy();
    await act(async () => { resolveSecond({ code: 200, data: report(2, 'second') } as never); });
    expect(screen.queryByText('secret 2')).toBeNull();
    fireEvent.click(buttons[1]);
    expect(screen.queryByText('secret 1')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    await act(async () => { resolveClosed({ code: 200, data: report(2, 'second') } as never); });
    await waitFor(() => expect(screen.queryByText('secret 2')).toBeNull());
  });
});
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

describe('report history failures', () => {
  it.each(['envelope', 'http', 'network'])('shows %s failure and recovers without a false empty table', async (kind) => {
    if (kind === 'envelope') vi.mocked(getInspectReports).mockResolvedValueOnce({ code: 503 } as never);
    else vi.mocked(getInspectReports).mockRejectedValueOnce(kind === 'http' ? { response: { data: { code: 503 } } } : new Error('offline'));
    vi.mocked(getInspectReports).mockResolvedValueOnce({ code: 200, data: { content: [], totalElements: 0 } } as never);
    render(<AiReports />);
    const error = kind === 'network' ? 'Unable to load data. Check your connection and retry; contact an administrator if the problem persists.' : 'Permissions or resource identity cannot currently be verified. Please contact an administrator.';
    expect((await screen.findByText(error)).closest('[role="alert"]')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /refresh/i }));
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(getInspectReports).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(error)).toBeNull();
  });
});
