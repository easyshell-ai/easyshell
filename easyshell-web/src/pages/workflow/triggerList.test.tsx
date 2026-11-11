import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import { getWorkflowDetail, getWorkflowPage, getWorkflowVersion, triggerWorkflowRun } from './api';

i18next.use(initReactI18next).init({ resources: { 'en-US': { translation: enUS } }, lng: 'en-US', interpolation: { escapeValue: false } });

const definition = { schemaVersion: 1, defaultOnUpstreamFailure: 'skip', onRejection: 'abort', nodes: [], edges: [], variables: { published: { type: 'string', required: false } } };
const workflow = { id: 2, name: 'Example', status: 'published', currentVersionId: 42, currentVersion: 1, visibility: 'private', runCount: 0, successCount: 0, createdAt: '' };
const detail = { workflow, versions: [], draft: { definition: { ...definition, variables: { draft: { type: 'string' } } } } };

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getWorkflowPage).mockResolvedValue({ code: 200, data: { content: [workflow], totalElements: 1, totalPages: 1, size: 20, number: 0 } } as never);
  vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: detail } as never);
  vi.mocked(getWorkflowVersion).mockResolvedValue({ code: 200, data: { id: 42, version: 1, definition } } as never);
  vi.mocked(triggerWorkflowRun).mockResolvedValue({ code: 200, data: { id: 'run-1' } } as never);
});
afterEach(async () => { cleanup(); await new Promise((resolve) => setTimeout(resolve, 0)); });

describe('list trigger published version', () => {
  it('shows published inputs and pins the confirmed version after a republish', async () => {
    render(<MemoryRouter><WorkflowListPage /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.trigger.action')) }));
    await screen.findByTestId('trigger-variable-published');
    expect(screen.queryByTestId('trigger-variable-draft')).toBeNull();
    expect(within(screen.getByRole('dialog')).getByText(/v1/)).toBeTruthy();
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: { ...detail, workflow: { ...workflow, currentVersionId: 43 } } } as never);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('workflow.trigger.submit') }));
    await waitFor(() => expect(triggerWorkflowRun).toHaveBeenCalledWith(2, expect.objectContaining({ versionId: 42 })));
  });

  it('refuses an unpublished workflow and a failed version load', async () => {
    vi.mocked(getWorkflowDetail).mockResolvedValueOnce({ code: 200, data: { ...detail, workflow: { ...workflow, currentVersionId: null } } } as never);
    vi.mocked(getWorkflowVersion).mockRejectedValueOnce(new Error('offline'));
    render(<MemoryRouter><WorkflowListPage /></MemoryRouter>);
    const button = await screen.findByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.trigger.action')) });
    fireEvent.click(button);
    await waitFor(() => expect(getWorkflowDetail).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(button);
    await waitFor(() => expect(getWorkflowVersion).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
