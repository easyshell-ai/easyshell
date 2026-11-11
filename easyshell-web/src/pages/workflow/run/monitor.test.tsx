import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../api', () => ({ getWorkflowRun: vi.fn(), getWorkflowVersion: vi.fn(), cancelWorkflowRun: vi.fn(), testRunNode: vi.fn() }));
vi.mock('../editor/canvas/WorkflowCanvas', () => ({ default: () => <div /> }));
vi.mock('./RunLogPanel', () => ({ default: () => <div /> }));
vi.mock('react-i18next', () => { const t = (key: string) => key; return { useTranslation: () => ({ t }) }; });

import { getWorkflowRun, getWorkflowVersion } from '../api';
import Monitor from './monitor';

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe('run detail provenance', () => {
  it('clears previously visible snapshots when refresh is forbidden', async () => {
    vi.mocked(getWorkflowRun).mockResolvedValueOnce({ code: 200, data: { run: { id: 'r1', workflowId: 1, versionId: 2, versionNo: 1, status: 'succeeded', triggerType: 'manual', input: { secret: 'old-sensitive-value' } }, nodeRuns: [] } } as never)
      .mockResolvedValueOnce({ code: 403 } as never);
    vi.mocked(getWorkflowVersion).mockResolvedValue({ code: 403 } as never);
    render(<MemoryRouter initialEntries={['/workflow/run/r1']}><Routes><Route path="/workflow/run/:runId" element={<Monitor />} /></Routes></MemoryRouter>);
    expect(await screen.findByText(/old-sensitive-value/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /common.refresh/ }));
    await waitFor(() => expect(screen.getByText('workflow.error.403')).toBeTruthy());
    expect(screen.queryByText(/old-sensitive-value/)).toBeNull();
  });
});
