import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUS from '../../../i18n/locales/en-US.json';

vi.mock('../api', () => ({ getWorkflowRuns: vi.fn(), cancelWorkflowRun: vi.fn() }));

import { cancelWorkflowRun, getWorkflowRuns } from '../api';
import WorkflowRunListPage from './index';

i18next.use(initReactI18next).init({ resources: { 'en-US': { translation: enUS } }, lng: 'en-US', interpolation: { escapeValue: false } });

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe('workflow run list actions', () => {
  it('keeps both actions accessible in a wide, wrapping fixed column', async () => {
    vi.mocked(getWorkflowRuns).mockResolvedValue({ code: 200, data: { content: [{ id: 'run-1', workflowId: 1, versionNo: 1, status: 'running', triggerType: 'manual', createdAt: '' }], totalElements: 1, totalPages: 1, size: 20, number: 0 } } as never);
    render(<MemoryRouter initialEntries={['/workflow/run']}><Routes><Route path="/workflow/run" element={<WorkflowRunListPage />} /><Route path="/workflow/run/:id" element={<div>Monitor destination</div>} /></Routes></MemoryRouter>);

    const monitor = await screen.findByRole('button', { name: (name) => name.endsWith(i18next.t('workflow.run.monitor')) });
    const row = monitor.closest('tr')!;
    const cancel = within(row).getByRole('button', { name: i18next.t('workflow.run.cancel') });
    expect((cancel as HTMLButtonElement).disabled).toBe(false);
    const cell = monitor.closest('td')!;
    expect(cell.contains(cancel)).toBe(true);
    expect((cell.querySelector('.ant-space') as HTMLElement).style.flexWrap).toBe('wrap');
    const table = cell.closest('table')!;
    const columns = table.querySelectorAll('col');
    expect(columns[columns.length - 1].style.width).toBe('220px');
    fireEvent.click(monitor);
    await waitFor(() => expect(screen.getByText('Monitor destination')).toBeTruthy());
  });

  it('requests cancellation and updates the safe summary', async () => {
    const run = { id: 'run-1', workflowId: 1, versionNo: 1, status: 'running', triggerType: 'manual', createdAt: '' };
    vi.mocked(getWorkflowRuns).mockResolvedValue({ code: 200, data: { content: [run], totalElements: 1, totalPages: 1, size: 20, number: 0 } } as never);
    vi.mocked(cancelWorkflowRun).mockResolvedValue({ code: 200, data: { ...run, cancelRequested: true } } as never);
    render(<MemoryRouter><WorkflowRunListPage /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: i18next.t('workflow.run.cancel') }));
    fireEvent.click(await screen.findByRole('button', { name: 'OK' }));
    await waitFor(() => expect(cancelWorkflowRun).toHaveBeenCalledWith('run-1'));
    await waitFor(() => expect((screen.getByRole('button', { name: i18next.t('workflow.run.cancel') }) as HTMLButtonElement).disabled).toBe(true));
  });
});
