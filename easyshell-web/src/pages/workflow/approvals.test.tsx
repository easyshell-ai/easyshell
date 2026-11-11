import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUS from '../../i18n/locales/en-US.json';

vi.mock('./api', () => ({ getPendingWorkflowApprovals: vi.fn(), decideWorkflowApproval: vi.fn() }));
import WorkflowApprovalsPage from './approvals';
import { decideWorkflowApproval, getPendingWorkflowApprovals } from './api';

i18next.use(initReactI18next).init({ resources: { 'en-US': { translation: enUS } }, lng: 'en-US', interpolation: { escapeValue: false } });
const restricted = { id: 1, status: 'pending', expiresAt: null, canReject: true, canApprove: false, title: 'secret title', detail: { secret: 'secret detail' }, riskLevel: 'LOW' };
const full = { id: 2, status: 'pending', title: 'Visible title', runId: 'run-2', nodeType: 'approval', nodeKey: 'node', detail: { note: 'visible detail' }, riskLevel: 'HIGH', createdAt: '' };

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => { vi.resetAllMocks(); });
afterEach(cleanup);

describe('workflow approval visibility', () => {
  it('hides restricted data, prevents approval and permits rejection while preserving full items', async () => {
    vi.mocked(getPendingWorkflowApprovals).mockResolvedValue({ code: 200, data: [restricted, full] } as never);
    vi.mocked(decideWorkflowApproval).mockResolvedValue({ code: 200 } as never);
    render(<WorkflowApprovalsPage />);
    const restrictedRow = (await screen.findByText(i18next.t('workflow.approval.restrictedReason'))).closest('tr')!;
    expect(within(restrictedRow).queryByText('secret title')).toBeNull();
    expect(within(restrictedRow).queryByText('LOW')).toBeNull();
    expect((within(restrictedRow).getByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.approval.approve')) }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(restrictedRow).getByRole('button', { name: i18next.t('common.detail') }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(restrictedRow).getByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.approval.reject')) }));
    fireEvent.click(await screen.findByRole('button', { name: i18next.t('common.confirm') }));
    await waitFor(() => expect(decideWorkflowApproval).toHaveBeenCalledWith(1, { decision: 'rejected' }));
    const fullRow = screen.getByText('Visible title').closest('tr')!;
    expect(within(fullRow).getByText('HIGH')).toBeTruthy();
    fireEvent.click(within(fullRow).getByRole('button', { name: i18next.t('common.detail') }));
    expect(await screen.findByText(/visible detail/)).toBeTruthy();
  });

  it.each([{ code: 403 }, new Error('network')])('clears stale data when a decision fails with %s', async (failure) => {
    vi.mocked(getPendingWorkflowApprovals).mockResolvedValue({ code: 200, data: [full] } as never);
    if (failure instanceof Error) vi.mocked(decideWorkflowApproval).mockRejectedValue(failure);
    else vi.mocked(decideWorkflowApproval).mockResolvedValue(failure as never);
    render(<WorkflowApprovalsPage />);
    fireEvent.click(within((await screen.findByText('Visible title')).closest('tr')!).getByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.approval.approve')) }));
    fireEvent.click(await screen.findByRole('button', { name: i18next.t('common.confirm') }));
    await screen.findByText(i18next.t('workflow.approval.dataUnavailable'));
    expect(screen.queryByText('Visible title')).toBeNull();
  });
});
