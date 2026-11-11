import { describe, expect, it, vi, beforeAll, beforeEach, afterEach } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../i18n/locales/zh-CN.json';
import enUS from '../../../i18n/locales/en-US.json';

// Static Ant Design messages render outside the test tree and can outlive jsdom.
vi.mock('antd', async (importOriginal) => {
  const antd = await importOriginal<typeof import('antd')>();
  return { ...antd, message: { ...antd.message, success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } };
});

vi.mock('../api', () => ({
  getWorkflowDetail: vi.fn(async () => ({
    code: 200,
    data: {
      workflow: { id: 2, name: 'Smoke', status: 'draft', visibility: 'private', runCount: 0, successCount: 0, createdBy: 1, createdAt: '' },
      versions: [],
      draft: {
        id: 1,
        version: 1,
        baseVersion: 0,
        isValid: false,
        createdAt: '',
        definition: {
          schemaVersion: 1,
          defaultOnUpstreamFailure: 'skip',
          onRejection: 'abort',
          variables: { target: { type: 'string', required: true } },
          nodes: [
            { key: 'start', type: 'start', name: 'Start', position: { x: 0, y: 0 }, config: {} },
            { key: 'end', type: 'end', name: 'End', position: { x: 600, y: 0 }, config: { outputs: [] } },
          ],
          edges: [],
        },
      },
    },
  })),
  saveWorkflowDraft: vi.fn(),
  getWorkflowVersion: vi.fn(),
  validateWorkflow: vi.fn(async () => ({ code: 200, data: { valid: true, issues: [] } })),
  publishWorkflow: vi.fn(),
  triggerWorkflowRun: vi.fn(),
  issuesOf: (data: { issues?: unknown }) => (Array.isArray(data?.issues) ? data.issues : []),
}));

import WorkflowEditorPage from './index';
import { getWorkflowDetail, getWorkflowVersion, triggerWorkflowRun, saveWorkflowDraft } from '../api';

i18next.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, 'en-US': { translation: enUS } },
  lng: 'en-US',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

beforeAll(() => {
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1440 });
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

beforeEach(() => vi.clearAllMocks());

afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

function renderEditor() {
  const router = createMemoryRouter([{ path: '/workflow/editor/:id', element: <WorkflowEditorPage /> }], { initialEntries: ['/workflow/editor/2'] });
  return render(<RouterProvider router={router} />);
}

describe('WorkflowEditorPage smoke', () => {
  it('renders toolbar, palette, canvas, empty state and definition JSON', async () => {
    renderEditor();

    await waitFor(() => expect(screen.getByTestId('workflow-canvas')).toBeTruthy());
    expect(screen.getByTestId('palette-start')).toBeTruthy();
    expect(screen.getByTestId('palette-script-exec')).toBeTruthy();
    expect(screen.getByTestId('save-draft')).toBeTruthy();
    expect(screen.getByTestId('open-publish')).toBeTruthy();
    expect(screen.getByTestId('open-trigger')).toBeTruthy();
    expect(screen.getByTestId('workflow-help')).toBeTruthy();
    expect(screen.getByTestId('workflow-templates')).toBeTruthy();
    expect(screen.getByTestId('validate-workflow')).toBeTruthy();
    expect(screen.queryByTestId('node-inspector')).toBeNull();
    expect(screen.getByTestId('canvas-empty-state')).toBeTruthy();
    fireEvent.click(within(screen.getByTestId('canvas-empty-state')).getByRole('button', { name: 'Close' }));
    expect(screen.queryByTestId('canvas-empty-state')).toBeNull();
    expect(screen.getByText('Definition JSON (read-only)')).toBeTruthy();
  });

  it('uses the published version instead of the draft and pins it on submit', async () => {
    const original = await vi.mocked(getWorkflowDetail)(2);
    const detail = { ...original.data, workflow: { ...original.data.workflow, currentVersionId: 42 } };
    if (!detail.draft) throw new Error('test fixture requires a draft');
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: detail } as never);
    vi.mocked(getWorkflowVersion).mockResolvedValue({ code: 200, data: { ...detail.draft, id: 42, version: 1, definition: { ...detail.draft.definition, variables: { published: { type: 'string', required: false } } } } } as never);
    vi.mocked(triggerWorkflowRun).mockResolvedValue({ code: 200, data: { id: 'run-1' } } as never);
    renderEditor();
    await screen.findByTestId('open-trigger');
    fireEvent.click(screen.getByTestId('open-trigger'));
    await screen.findByTestId('trigger-variable-published');
    expect(screen.queryByTestId('trigger-variable-target')).toBeNull();
    expect(within(screen.getByRole('dialog')).getByText(/v1/)).toBeTruthy();
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: { ...detail, workflow: { ...detail.workflow, currentVersionId: 43 } } } as never);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: i18next.t('workflow.trigger.submit') }));
    await waitFor(() => expect(triggerWorkflowRun).toHaveBeenCalledWith(2, expect.objectContaining({ versionId: 42 })));
  });

  it('does not open a trigger form without a published version or when loading it fails', async () => {
    const original = await vi.mocked(getWorkflowDetail)(2);
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: { ...original.data, workflow: { ...original.data.workflow, currentVersionId: null } } } as never);
    renderEditor();
    await screen.findByTestId('open-trigger');
    fireEvent.click(screen.getByTestId('open-trigger'));
    await waitFor(() => expect(getWorkflowDetail).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole('dialog')).toBeNull();
    vi.mocked(getWorkflowDetail).mockResolvedValue({ code: 200, data: { ...original.data, workflow: { ...original.data.workflow, currentVersionId: 42 } } } as never);
    vi.mocked(getWorkflowVersion).mockRejectedValueOnce(new Error('offline'));
    fireEvent.click(screen.getByTestId('open-trigger'));
    await waitFor(() => expect(getWorkflowVersion).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps the complete dirty draft across editor routes and blocks leaving', async () => {
    const original = await vi.mocked(getWorkflowDetail)(2);
    if (!original.data.draft) throw new Error('test fixture requires a draft');
    const empty = { ...original.data.draft, definition: { ...original.data.draft.definition, nodes: [] } };
    vi.mocked(getWorkflowDetail).mockImplementation(async (id) => ({ code: 200, data: { ...original.data, workflow: { ...original.data.workflow, id, name: `Workflow ${id}` }, draft: empty } }) as never);
    const router = createMemoryRouter([
      { path: '/workflow/editor/:id', element: <WorkflowEditorPage /> },
      { path: '/workflow', element: <div>Workflow list</div> },
    ], { initialEntries: ['/workflow/editor/2'] });
    render(<RouterProvider router={router} />);
    fireEvent.click(await screen.findByTestId('complete-entry-exit'));
    await screen.findByText('Unsaved workflow changes');
    await act(async () => { await router.navigate('/workflow'); });
    expect(router.state.location.pathname).toBe('/workflow/editor/2');
    await act(async () => { await router.navigate('/workflow/editor/3'); });
    await screen.findByText('Workflow 3');
    expect(await screen.findByRole('button', { name: 'Return to workflow #2' })).toBeTruthy();
    await act(async () => { await router.navigate('/workflow'); });
    expect(router.state.location.pathname).toBe('/workflow/editor/3');
    fireEvent.click(screen.getByRole('button', { name: 'Return to workflow #2' }));
    await screen.findByText('Workflow 2');
    await waitFor(() => expect(screen.queryByTestId('complete-entry-exit')).toBeNull());
    expect(screen.getByText('Unsaved workflow changes')).toBeTruthy();
  });
});
