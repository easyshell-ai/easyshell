import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUS from '../../i18n/locales/en-US.json';

vi.mock('./api', () => ({
  getWorkflowPage: vi.fn(), getWorkflowDetail: vi.fn(), getWorkflowVersion: vi.fn(),
  triggerWorkflowRun: vi.fn(), archiveWorkflow: vi.fn(), createWorkflow: vi.fn(),
  publishWorkflow: vi.fn(), saveWorkflowDraft: vi.fn(), validateWorkflow: vi.fn(), issuesOf: () => [],
}));

import WorkflowListPage from './index';
import { getWorkflowDetail, getWorkflowPage, publishWorkflow, validateWorkflow } from './api';

i18next.use(initReactI18next).init({ resources: { 'en-US': { translation: enUS } }, lng: 'en-US', interpolation: { escapeValue: false } });

const workflows = [1, 2].map((id) => ({ id, name: `Workflow ${id}`, status: 'draft', visibility: 'private', runCount: 0, successCount: 0, createdAt: '' }));
const detail = (id: number) => ({ code: 200, data: { draft: { id: id * 10, baseVersion: id, definitionSha256: `sha-${id}`, definition: { nodes: [], edges: [] } } } });
const deferred = () => {
  let resolve!: (value: never) => void;
  const promise = new Promise<never>((done) => { resolve = done; });
  return { promise, resolve };
};

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getWorkflowPage).mockResolvedValue({ code: 200, data: { content: workflows, totalElements: 2, totalPages: 1, size: 20, number: 0 } } as never);
  vi.mocked(validateWorkflow).mockResolvedValue({ code: 200, data: { valid: true, issues: [] } } as never);
  vi.mocked(publishWorkflow).mockResolvedValue({ code: 200 } as never);
});
afterEach(cleanup);

const publishButton = (name: string) => within(screen.getByText(name).closest('tr')!).getByRole('button', { name: (label) => label.endsWith(i18next.t('workflow.publish.action')) });

describe('list publish request isolation', () => {
  it('keeps B and its validation token when cancelled A completes late', async () => {
    const a = deferred();
    vi.mocked(getWorkflowDetail).mockReturnValueOnce(a.promise).mockResolvedValueOnce(detail(2) as never);
    render(<MemoryRouter><WorkflowListPage /></MemoryRouter>);
    await screen.findByText('Workflow 1');
    fireEvent.click(publishButton('Workflow 1'));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('common.cancel') }));
    fireEvent.click(publishButton('Workflow 2'));
    await waitFor(() => expect(validateWorkflow).toHaveBeenCalledWith(2, expect.anything()));
    await act(async () => { a.resolve(detail(1) as never); await a.promise; });
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Workflow 2')).toBeTruthy();
    expect(validateWorkflow).toHaveBeenCalledTimes(1);
    fireEvent.click(within(dialog).getByRole('button', { name: (label) => label.endsWith(i18next.t('workflow.publish.confirm')) }));
    await waitFor(() => expect(publishWorkflow).toHaveBeenCalledWith(2, { draftId: 20, baseVersion: 2, definitionSha256: 'sha-2' }));
  });

  it('does not reopen after cancellation while validation is pending', async () => {
    const validation = deferred();
    vi.mocked(getWorkflowDetail).mockResolvedValue(detail(1) as never);
    vi.mocked(validateWorkflow).mockReturnValue(validation.promise);
    render(<MemoryRouter><WorkflowListPage /></MemoryRouter>);
    await screen.findByText('Workflow 1');
    fireEvent.click(publishButton('Workflow 1'));
    await waitFor(() => expect(validateWorkflow).toHaveBeenCalledTimes(1));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('common.cancel') }));
    await act(async () => { validation.resolve({ code: 200, data: { valid: true, issues: [] } } as never); await validation.promise; });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(publishWorkflow).not.toHaveBeenCalled();
  });
});
