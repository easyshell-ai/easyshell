import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, Link, RouterProvider, useNavigate } from 'react-router-dom';

const mocks = vi.hoisted(() => ({ detail: vi.fn(), version: vi.fn(), discard: vi.fn(), begin: vi.fn(), end: vi.fn(), unsaved: vi.fn(), memoryIds: vi.fn(), dirty: vi.fn() }));
vi.mock('../api', () => ({ getWorkflowDetail: mocks.detail, getWorkflowVersion: mocks.version, issuesOf: () => [], publishWorkflow: vi.fn(), triggerWorkflowRun: vi.fn(), validateWorkflow: vi.fn() }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('./canvas/WorkflowCanvas', () => ({ default: () => null }));
vi.mock('./NodePalette', () => ({ default: () => null }));
vi.mock('./DefinitionJsonView', () => ({ default: () => null }));
vi.mock('./inspector/NodeInspector', () => ({ NodeInspector: () => null }));
vi.mock('./inspector/HelpDrawer', () => ({ HelpDrawer: () => null }));
vi.mock('./templates/TemplateGallery', () => ({ TemplateGallery: () => null }));
vi.mock('./modals', () => ({
  EdgeEditModal: () => null, PublishModal: () => null, TriggerModal: () => null,
  VersionConflictModal: ({ open, busy, onDiscard }: { open: boolean; busy: boolean; onDiscard: () => void }) => open ? <button onClick={onDiscard} disabled={busy}>discard</button> : null,
}));
vi.mock('./useWorkflowEditor', () => ({
  snapshotOf: vi.fn(),
  useWorkflowEditor: ({ draft, onVersionConflict }: { draft: { definition: { nodes: unknown[]; edges: unknown[] } }; onVersionConflict: (value: unknown) => void }) => ({
    definition: draft.definition, nodes: [], edges: [], dirty: mocks.dirty(),
    beginDiscard: mocks.begin, endDiscard: mocks.end, discardAndReload: mocks.discard,
    hasUnsavedMemoryDrafts: mocks.unsaved, hasUnsavedChanges: mocks.unsaved, memoryDraftIds: mocks.memoryIds(),
    onNodesChange: vi.fn(), onEdgesChange: vi.fn(),
    saveNow: () => { onVersionConflict({ target: null }); return Promise.resolve(false); },
  }),
}));

import WorkflowEditorPage from './index';

const definition = { schemaVersion: 1, defaultOnUpstreamFailure: 'skip', onRejection: 'abort', variables: {}, nodes: [], edges: [] };
const draft = { id: 12, version: 1, baseVersion: 1, isValid: false, createdAt: '', definition };
const detail = (id: number, currentDraft: typeof draft | null = draft, currentVersionId: number | null = null) => ({ code: 200, data: { workflow: { id, name: `Workflow ${id}`, status: 'draft', currentVersionId }, versions: [], draft: currentDraft } });
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}
function Switcher() {
  const navigate = useNavigate();
  return <><button onClick={() => navigate('/workflow/editor/2')}>switch</button><Link to="/elsewhere">leave</Link><WorkflowEditorPage /></>;
}
function setup() {
  const router = createMemoryRouter([{ path: '/workflow/editor/:id', element: <Switcher /> }, { path: '/elsewhere', element: <div>elsewhere</div> }], { initialEntries: ['/elsewhere', '/workflow/editor/1'], initialIndex: 1 });
  return { ...render(<RouterProvider router={router} />), router };
}
async function openConflict() { fireEvent.click(await screen.findByTestId('save-draft')); await screen.findByText('discard'); }

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => { vi.resetAllMocks(); mocks.discard.mockReturnValue(true); mocks.begin.mockResolvedValue({ epoch: 1, generation: 1 }); mocks.memoryIds.mockReturnValue([]); });
afterEach(async () => {
  cleanup();
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
});

describe('workflow editor discard and detail loading', () => {
  it('discards to an empty draft when no draft or published version exists', async () => {
    mocks.detail.mockResolvedValueOnce(detail(1)).mockResolvedValueOnce(detail(1, null));
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    await waitFor(() => expect(mocks.discard).toHaveBeenCalledWith(expect.objectContaining({ id: 0, definition }), { epoch: 1, generation: 1 }));
    expect(mocks.version).not.toHaveBeenCalled();
    expect(screen.queryByText('discard')).toBeNull();
  });

  it('loads the published definition when discarding without a draft', async () => {
    mocks.detail.mockResolvedValueOnce(detail(1)).mockResolvedValueOnce(detail(1, null, 42));
    mocks.version.mockResolvedValueOnce({ code: 200, data: { ...draft, id: 42, version: 3 } });
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    await waitFor(() => expect(mocks.discard).toHaveBeenCalledWith(expect.objectContaining({ id: -1, version: 0, definition }), expect.anything()));
  });

  it('keeps the conflict open when the published version request fails', async () => {
    mocks.detail.mockResolvedValueOnce(detail(1)).mockResolvedValueOnce(detail(1, null, 42));
    mocks.version.mockRejectedValueOnce(new Error('offline'));
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    await waitFor(() => expect(mocks.version).toHaveBeenCalled());
    await waitFor(() => expect((screen.getByText('discard') as HTMLButtonElement).disabled).toBe(false));
    expect(mocks.discard).not.toHaveBeenCalled();
  });

  it('keeps the conflict open when edits invalidate the discard checkpoint', async () => {
    const pending = deferred<ReturnType<typeof detail>>();
    mocks.detail.mockResolvedValueOnce(detail(1)).mockReturnValueOnce(pending.promise);
    mocks.discard.mockReturnValueOnce(false);
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    await waitFor(() => expect(mocks.detail).toHaveBeenCalledTimes(2));
    await act(async () => pending.resolve(detail(1)));
    expect(mocks.discard).toHaveBeenCalledWith(draft, { epoch: 1, generation: 1 });
    expect((screen.getByText('discard') as HTMLButtonElement).disabled).toBe(false);
  });

  it('ignores an old discard response after navigating to another workflow', async () => {
    const pending = deferred<ReturnType<typeof detail>>();
    mocks.detail.mockResolvedValueOnce(detail(1)).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(detail(2));
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    await waitFor(() => expect(mocks.detail).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByText('switch'));
    await screen.findByText('Workflow 2');
    await act(async () => pending.resolve(detail(1, null)));
    expect(mocks.discard).not.toHaveBeenCalled();
    expect(screen.getByText('Workflow 2')).toBeTruthy();
    await openConflict();
    expect((screen.getByText('discard') as HTMLButtonElement).disabled).toBe(false);
  });

  it('waits for beginDiscard before fetching the replacement and releases the checkpoint', async () => {
    const pending = deferred<{ epoch: number; generation: number }>();
    mocks.begin.mockReturnValueOnce(pending.promise);
    mocks.detail.mockResolvedValueOnce(detail(1)).mockResolvedValueOnce(detail(1, null));
    setup(); await openConflict(); fireEvent.click(screen.getByText('discard'));
    expect(mocks.begin).toHaveBeenCalledTimes(1);
    expect(mocks.detail).toHaveBeenCalledTimes(1);
    expect((screen.getByText('discard') as HTMLButtonElement).disabled).toBe(true);
    await act(async () => pending.resolve({ epoch: 1, generation: 1 }));
    await waitFor(() => expect(mocks.detail).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mocks.end).toHaveBeenCalledWith({ epoch: 1, generation: 1 }));
  });

  it('ignores a stale published-version response after navigation', async () => {
    const pending = deferred<{ code: number; data: typeof draft }>();
    mocks.detail.mockResolvedValueOnce(detail(1, null, 42)).mockResolvedValueOnce(detail(2));
    mocks.version.mockReturnValueOnce(pending.promise);
    setup(); await waitFor(() => expect(mocks.version).toHaveBeenCalled());
    fireEvent.click(screen.getByText('switch'));
    await screen.findByText('Workflow 2');
    await act(async () => pending.resolve({ code: 200, data: draft }));
    expect(screen.getByText('Workflow 2')).toBeTruthy();
  });

  it('blocks links and browser history while unsaved changes exist', async () => {
    mocks.detail.mockResolvedValue(detail(1));
    const { router } = setup();
    await screen.findByText('Workflow 1');
    mocks.unsaved.mockReturnValue(true);
    fireEvent.click(screen.getByText('leave'));
    await waitFor(() => expect(router.state.blockers.size).toBeGreaterThan(0));
    expect(router.state.location.pathname).toBe('/workflow/editor/1');
    await act(async () => { await router.navigate(-1); });
    expect(router.state.location.pathname).toBe('/workflow/editor/1');
    mocks.unsaved.mockReturnValue(false);
    fireEvent.click(screen.getByText('leave'));
    await screen.findByText('elsewhere');
  });

  it('requests a browser unload prompt only while memory drafts exist', async () => {
    mocks.detail.mockResolvedValue(detail(1));
    const { unmount } = setup();
    await screen.findByText('Workflow 1');
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    mocks.unsaved.mockReturnValue(true);
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
    unmount();
    const afterUnmount = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(afterUnmount);
    expect(afterUnmount.defaultPrevented).toBe(false);
  });

  it.each([403, 404, 500])('offers a return to the memory draft after a %s response', async (code) => {
    mocks.memoryIds.mockReturnValue([1]);
    mocks.unsaved.mockReturnValue(true);
    mocks.detail.mockImplementation(async (id: number) => id === 1 ? detail(1) : { code, message: 'failure' });
    const { router } = setup();
    await screen.findByText('Workflow 1');
    fireEvent.click(screen.getByText('switch'));
    await screen.findByText(code === 500 ? 'failure' : `workflow.error.${code}`);
    fireEvent.click(screen.getByRole('button', { name: 'workflow.editor.returnToMemoryDraft' }));
    await screen.findByText('Workflow 1');
    expect(router.state.location.pathname).toBe('/workflow/editor/1');
  });

  it('offers a return while the destination is still loading', async () => {
    const pending = deferred<ReturnType<typeof detail>>();
    mocks.memoryIds.mockReturnValue([1]);
    mocks.unsaved.mockReturnValue(true);
    mocks.detail.mockImplementation((id: number) => id === 1 ? Promise.resolve(detail(1)) : pending.promise);
    const { router } = setup();
    await screen.findByText('Workflow 1');
    fireEvent.click(screen.getByText('switch'));
    await waitFor(() => expect(mocks.detail).toHaveBeenCalledWith(2));
    fireEvent.click(screen.getByRole('button', { name: 'workflow.editor.returnToMemoryDraft' }));
    await screen.findByText('Workflow 1');
    await act(async () => pending.resolve(detail(2)));
    expect(router.state.location.pathname).toBe('/workflow/editor/1');
  });

  it('shows the memory-only warning for unsaved edits and retained drafts after switching', async () => {
    mocks.dirty.mockReturnValue(true);
    mocks.detail.mockImplementation(async (id: number) => detail(id));
    const view = setup();
    await screen.findByText('Workflow 1');
    expect(screen.getByText('workflow.editor.memoryOnlyWarning')).toBeTruthy();

    mocks.dirty.mockReturnValue(false);
    mocks.memoryIds.mockReturnValue([1]);
    fireEvent.click(screen.getByText('switch'));
    await screen.findByText('Workflow 2');
    expect(screen.getByText('workflow.editor.memoryOnlyWarning')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'workflow.editor.returnToMemoryDraft' })).toBeTruthy();
    view.unmount();
  });

  it.each(['9007199254740992', '0', '1.5', '01'])('rejects invalid destination id %s while unsaved', async (id) => {
    mocks.detail.mockResolvedValue(detail(1));
    const { router } = setup();
    await screen.findByText('Workflow 1');
    mocks.unsaved.mockReturnValue(true);
    await act(async () => { await router.navigate(`/workflow/editor/${id}`); });
    expect(router.state.location.pathname).toBe('/workflow/editor/1');
    expect(mocks.detail).toHaveBeenCalledTimes(1);
  });

  it.each(['9007199254740992', '0', '1.5', '01'])('does not fetch an invalid initial id %s', async (id) => {
    const router = createMemoryRouter([{ path: '/workflow/editor/:id', element: <WorkflowEditorPage /> }], { initialEntries: [`/workflow/editor/${id}`] });
    render(<RouterProvider router={router} />);
    await screen.findByText('workflow.error.404');
    expect(mocks.detail).not.toHaveBeenCalled();
  });
});
