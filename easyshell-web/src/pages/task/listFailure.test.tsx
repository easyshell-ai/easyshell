import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue || key }) }));
vi.mock('../../api/task', () => ({ getTaskList: vi.fn(), getTaskDetail: vi.fn(), createTask: vi.fn(), submitTaskForApproval: vi.fn() }));
vi.mock('../../api/script', () => ({ getScriptList: vi.fn(), getScriptParameters: vi.fn() }));
vi.mock('../../api/host', () => ({ getHostList: vi.fn() }));
vi.mock('../../api/cluster', () => ({ getClusterList: vi.fn() }));
vi.mock('../../api/tag', () => ({ getTagList: vi.fn() }));
vi.mock('../workflow/api', () => ({ getWorkflowPage: vi.fn(), getWorkflowDetail: vi.fn(), getWorkflowVersion: vi.fn(), triggerWorkflowRun: vi.fn() }));
vi.mock('@uiw/react-codemirror', () => ({ default: () => null }));

import TaskPage from './index';
import { getTaskList } from '../../api/task';
import { getScriptList } from '../../api/script';
import { getHostList } from '../../api/host';
import { getClusterList } from '../../api/cluster';
import { getTagList } from '../../api/tag';

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => {
  vi.resetAllMocks();
  for (const mock of [getScriptList, getHostList, getClusterList, getTagList]) vi.mocked(mock).mockResolvedValue({ code: 200, data: [] } as never);
});
afterEach(cleanup);

describe('task list failures', () => {
  it.each(['envelope', 'http', 'network'])('shows %s failure instead of an empty table and recovers', async (kind) => {
    if (kind === 'envelope') vi.mocked(getTaskList).mockResolvedValueOnce({ code: 503 } as never);
    else vi.mocked(getTaskList).mockRejectedValueOnce(kind === 'http' ? { response: { data: { code: 503 } } } : new Error('offline'));
    vi.mocked(getTaskList).mockResolvedValueOnce({ code: 200, data: [] } as never);
    render(<MemoryRouter><TaskPage /></MemoryRouter>);
    const error = kind === 'network' ? 'Unable to load data. Check your connection and retry; contact an administrator if the problem persists.' : 'Permissions or resource identity cannot currently be verified. Please contact an administrator.';
    const alert = (await screen.findByText(error)).closest('[role="alert"]') as HTMLElement;
    expect(alert).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByText(/Historical tasks whose identity/)).toBeNull();
    fireEvent.click(within(alert).getByRole('button', { name: /refresh/i }));
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(screen.getByText(/Historical tasks whose identity/)).toBeTruthy();
    expect(getTaskList).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(error)).toBeNull();
  });

  it.each([
    ['empty', []],
    ['non-empty', [{ id: 'task-1', name: 'Visible task', status: 0, successCount: 0, failedCount: 0, totalCount: 1 }]],
  ])('shows the visibility notice for a successful %s list', async (_label, tasks) => {
    vi.mocked(getTaskList).mockResolvedValue({ code: 200, data: tasks } as never);
    render(<MemoryRouter><TaskPage /></MemoryRouter>);
    expect(await screen.findByText('Historical tasks whose identity has not been verified and workflow tasks are temporarily not shown in this list. The data has not been deleted.')).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    if (tasks.length) expect(screen.getByText('Visible task')).toBeTruthy();
  });
});
