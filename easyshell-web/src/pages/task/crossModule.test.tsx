import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { message } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUS from '../../i18n/locales/en-US.json';

vi.mock('../../api/task', () => ({ getTaskList: vi.fn(), getTaskDetail: vi.fn(), createTask: vi.fn(), submitTaskForApproval: vi.fn() }));
vi.mock('../../api/script', () => ({ getScriptList: vi.fn(), getScriptParameters: vi.fn() }));
vi.mock('../../api/host', () => ({ getHostList: vi.fn() }));
vi.mock('../../api/myHostPermissions', () => ({ queryMyHostPermissions: vi.fn() }));
vi.mock('../../api/cluster', () => ({ getClusterList: vi.fn() }));
vi.mock('../../api/tag', () => ({ getTagList: vi.fn() }));
vi.mock('../workflow/api', () => ({ getWorkflowPage: vi.fn(), getWorkflowDetail: vi.fn(), getWorkflowVersion: vi.fn(), triggerWorkflowRun: vi.fn() }));
vi.mock('@uiw/react-codemirror', () => ({ default: () => null }));

import TaskPage from './index';
import { getTaskDetail, getTaskList } from '../../api/task';
import { getScriptList } from '../../api/script';
import { getHostList } from '../../api/host';
import { queryMyHostPermissions } from '../../api/myHostPermissions';
import { getClusterList } from '../../api/cluster';
import { getTagList } from '../../api/tag';
import { getWorkflowPage, getWorkflowDetail, getWorkflowVersion, triggerWorkflowRun } from '../workflow/api';

i18next.use(initReactI18next).init({ resources: { 'en-US': { translation: enUS } }, lng: 'en-US', interpolation: { escapeValue: false } });
const workflow = { id: 2, name: 'Owned workflow', status: 'published', currentVersionId: 42, currentVersion: 1 };
const definition = { schemaVersion: 1, nodes: [], edges: [], variables: { published_input: { type: 'string' } } };

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getTaskList).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(getScriptList).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(getHostList).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(queryMyHostPermissions).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(getClusterList).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(getTagList).mockResolvedValue({ code: 200, data: [] } as never);
  vi.mocked(getWorkflowPage).mockResolvedValue({ code: 200, data: { content: [workflow, { ...workflow, id: 3, name: 'Other public' }], number: 0, size: 20, totalElements: 2 } } as never);
  vi.mocked(getWorkflowDetail).mockImplementation(async (id) => (id === 3 ? { code: 403 } : { code: 200, data: { workflow } }) as never);
  vi.mocked(getWorkflowVersion).mockResolvedValue({ code: 200, data: { id: 42, version: 1, definition } } as never);
  vi.mocked(triggerWorkflowRun).mockResolvedValue({ code: 200, data: { id: 'run-1' } } as never);
});

describe('task host execute permissions', () => {
  const hosts = [{ id: 'yes', hostname: 'Allowed', ip: '1', status: 1 }, { id: 'no', hostname: 'View only', ip: '2', status: 1 }];

  it('disables view-only hosts and leaves executable hosts selectable', async () => {
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: hosts } as never);
    vi.mocked(queryMyHostPermissions).mockResolvedValue({ code: 200, data: [{ agentId: 'yes', execute: true }, { agentId: 'no', execute: false }] } as never);
    showPage();
    await waitFor(() => expect(queryMyHostPermissions).toHaveBeenCalledWith(['yes', 'no']));
    fireEvent.mouseDown(screen.getByLabelText(i18next.t('task.targetHost')));
    expect(await screen.findByText(/View only \(2\).*No execute permission/)).toBeTruthy();
    const denied = screen.getByText(/View only \(2\).*No execute permission/).closest('.ant-select-item');
    expect(denied?.classList.contains('ant-select-item-option-disabled')).toBe(true);
    expect(screen.getByText('Allowed (1)').closest('.ant-select-item')?.classList.contains('ant-select-item-option-disabled')).toBe(false);
  });

  it('treats failures as unknown and warns that dynamic targets are checked at execution', async () => {
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: hosts } as never);
    vi.mocked(queryMyHostPermissions).mockRejectedValue(new Error('offline'));
    showPage();
    await screen.findByText('Host permissions could not be checked. Retry before submitting.');
    expect((screen.getByRole('button', { name: (name) => name.endsWith(i18next.t('task.executeTask')) }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: (name) => name.endsWith(i18next.t('task.byCluster')) }));
    expect(screen.getByText(i18next.t('task.dynamicTargetsPermissionHint'))).toBeTruthy();
  });

  it('keeps omitted hosts unknown and retries their permissions', async () => {
    let resolveOld!: (value: never) => void;
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: hosts } as never);
    vi.mocked(queryMyHostPermissions).mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ code: 200, data: [] } as never);
    showPage();
    await waitFor(() => expect(queryMyHostPermissions).toHaveBeenCalledTimes(1));
    // Trigger a new query after the first one resolves with incomplete data.
    await act(async () => resolveOld({ code: 200, data: [] } as never));
    await screen.findByText('Some host permissions are unknown. Retry before selecting those hosts.');
    fireEvent.click(screen.getByRole('button', { name: i18next.t('common.refresh') }));
    await waitFor(() => expect(queryMyHostPermissions).toHaveBeenCalledTimes(2));
    expect((screen.getByRole('button', { name: (name) => name.endsWith(i18next.t('task.executeTask')) }) as HTMLButtonElement).disabled).toBe(false);
  });
});
afterEach(async () => {
  cleanup();
  // Ant Design's static messages live outside the React Testing Library root.
  await act(async () => message.destroy());
  if (document.querySelector('.ant-message-notice')) {
    await waitFor(() => expect(document.querySelector('.ant-message-fade-leave-active')).not.toBeNull());
    await act(async () => {
      document.querySelectorAll('.ant-message-notice').forEach((notice) => {
        fireEvent.animationEnd(notice);
        fireEvent.transitionEnd(notice);
      });
    });
    await waitFor(() => expect(document.querySelector('.ant-message-notice')).toBeNull());
  }
});

function showPage(path = '/task') {
  render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/task" element={<TaskPage />} /><Route path="/workflow/run/:id" element={<div>run destination</div>} /></Routes></MemoryRouter>);
}

describe('task cross-module entry', () => {
  it('filters unauthorized public workflows and pins the displayed published version', async () => {
    showPage();
    fireEvent.click(screen.getByRole('button', { name: (name) => name.endsWith(i18next.t('task.runWorkflow')) }));
    await screen.findByText('Owned workflow');
    expect(screen.queryByText('Other public')).toBeNull();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('workflow.trigger.action') }));
    const publishedInput = await screen.findByTestId('trigger-variable-published_input');
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: { workflow: { ...workflow, currentVersionId: 43 } } } as never);
    fireEvent.click(within(publishedInput.closest('[role="dialog"]') as HTMLElement).getByRole('button', { name: i18next.t('workflow.trigger.submit') }));
    await waitFor(() => expect(triggerWorkflowRun).toHaveBeenCalledWith(2, expect.objectContaining({ versionId: 42 })));
    await screen.findByText('run destination');
    await screen.findByText(i18next.t('workflow.trigger.success'));
  });

  it.each(['forbidden', 'missing', 'version failure'])('does not trigger on %s', async (failure) => {
    showPage();
    fireEvent.click(screen.getByRole('button', { name: (name) => name.endsWith(i18next.t('task.runWorkflow')) }));
    await screen.findByText('Owned workflow');
    if (failure === 'forbidden') vi.mocked(getWorkflowDetail).mockResolvedValueOnce({ code: 403 } as never);
    if (failure === 'missing') vi.mocked(getWorkflowDetail).mockResolvedValueOnce({ code: 200, data: { workflow: { ...workflow, currentVersionId: null } } } as never);
    if (failure === 'version failure') vi.mocked(getWorkflowVersion).mockResolvedValueOnce({ code: 403 } as never);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('workflow.trigger.action') }));
    await waitFor(() => expect(getWorkflowDetail).toHaveBeenCalledTimes(3));
    await screen.findByText(i18next.t(failure === 'missing' ? 'workflow.trigger.notPublished' : 'workflow.error.403'));
    expect(screen.queryByTestId('trigger-variable-published_input')).toBeNull();
    expect(triggerWorkflowRun).not.toHaveBeenCalled();
  });

  it('uses authorized task detail for deep links and ignores a forged run id', async () => {
    vi.mocked(getTaskDetail).mockResolvedValue({ code: 403 } as never);
    showPage('/task?taskId=task-1&runId=forged');
    await waitFor(() => expect(getTaskDetail).toHaveBeenCalledWith('task-1'));
    await screen.findByRole('alert');
    expect(screen.queryByText('forged')).toBeNull();
    expect(screen.queryByText('run destination')).toBeNull();
  });
});
