import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { TargetsEditor } from './NodeConfigFields';
import { EMPTY_TARGETS } from './nodeConfig';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue || key }) }));

beforeAll(() => {
  if (!('ResizeObserver' in window)) {
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

afterEach(cleanup);

describe('host target safety', () => {
  it.each(['loading', 'error', 'ready'] as const)('never offers free text in %s state and preserves saved targets', (hostStatus) => {
    const onChange = vi.fn();
    render(<TargetsEditor value={{ ...EMPTY_TARGETS, agentIds: ['old-host'] }} onChange={onChange} options={{ hostStatus, hosts: [] }} />);
    expect(screen.getByLabelText('workflow.form.agentIds').getAttribute('role')).toBe('combobox');
    expect(onChange).not.toHaveBeenCalled();
    if (hostStatus === 'ready') expect(screen.getByText(/saved targets are unavailable/)).toBeTruthy();
    if (hostStatus === 'error') expect(screen.getByText(/could not be fully checked/)).toBeTruthy();
    if (hostStatus === 'loading') expect(screen.getByText(/Checking available/)).toBeTruthy();
  });

  it('allows removing saved targets during failure without offering unchecked hosts', () => {
    const onChange = vi.fn();
    const retry = vi.fn();
    const { container } = render(<TargetsEditor value={{ ...EMPTY_TARGETS, agentIds: ['old-host'] }} onChange={onChange} options={{ hostStatus: 'error', hosts: [{ value: 'unchecked', label: 'Unchecked' }] }} onRetryHosts={retry} />);
    fireEvent.mouseDown(screen.getByLabelText('workflow.form.agentIds'));
    expect(screen.queryByText('Unchecked')).toBeNull();
    const remove = container.querySelector('.ant-select-selection-item-remove');
    expect(remove).toBeTruthy();
    fireEvent.click(remove!);
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ agentIds: [] }));
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledOnce();
  });

  it.each(['cluster', 'tag', 'variable'] as const)('explains runtime checks for %s targets', (mode) => {
    render(<TargetsEditor value={{ ...EMPTY_TARGETS, mode }} />);
    expect(screen.getByText(/permissions checked for each host at runtime/)).toBeTruthy();
  });
});
