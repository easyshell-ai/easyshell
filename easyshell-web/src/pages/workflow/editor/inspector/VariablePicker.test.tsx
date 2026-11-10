import { describe, expect, it, vi, beforeAll, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../../i18n/locales/zh-CN.json';
import enUS from '../../../../i18n/locales/en-US.json';
import { VariablePicker } from './VariablePicker';
import { formatVariableInsert, variableTestId } from './variableModel';
import type { WorkflowVariableGroup } from '../../variables';

i18next.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, 'en-US': { translation: enUS } },
  lng: 'en-US',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

beforeAll(() => {
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
  if (!('ResizeObserver' in window)) {
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

const groups: WorkflowVariableGroup[] = [
  {
    id: 'run-input',
    kind: 'input',
    label: 'run.input',
    refs: [{ path: 'run.input.target', label: 'target', type: 'string', required: true }],
  },
  {
    id: 'node:check',
    kind: 'node',
    label: 'Disk check',
    nodeKey: 'check',
    refs: [
      { path: 'nodes.check.output', label: 'output', type: 'json', nodeKey: 'check' },
      { path: 'nodes.check.output.usage', label: 'usage', type: 'number', nodeKey: 'check' },
    ],
  },
];

describe('formatVariableInsert', () => {
  it('template mode wraps the path in ${…}', () => {
    expect(formatVariableInsert('nodes.check.output.usage', 'template')).toBe('${nodes.check.output.usage}');
  });

  it('expression and path modes insert a bare path', () => {
    expect(formatVariableInsert('nodes.check.output.result', 'expression')).toBe('nodes.check.output.result');
    expect(formatVariableInsert('run.input.target', 'path')).toBe('run.input.target');
  });
});

describe('variableTestId', () => {
  it('sanitizes dots and other separators', () => {
    expect(variableTestId('nodes.check.output.usage')).toBe('variable-picker-item-nodes-check-output-usage');
    expect(variableTestId('run.input.target')).toBe('variable-picker-item-run-input-target');
  });
});

describe('VariablePicker', () => {
  it('lists groups with type badges and inserts the selected path', () => {
    const onInsert = vi.fn();
    render(<VariablePicker groups={groups} mode="template" onInsert={onInsert} />);

    fireEvent.click(screen.getByTestId('variable-picker'));
    expect(screen.getByTestId('variable-picker-panel')).toBeTruthy();
    expect(screen.getByText('${nodes.check.output.usage}')).toBeTruthy();
    expect(screen.getByText('Number')).toBeTruthy();
    expect(screen.getByText('required')).toBeTruthy();

    fireEvent.click(screen.getByTestId('variable-picker-item-nodes-check-output-usage'));
    expect(onInsert).toHaveBeenCalledWith('nodes.check.output.usage');
  });

  it('filters by search keyword and shows an empty state when nothing matches', () => {
    render(<VariablePicker groups={groups} mode="expression" onInsert={() => {}} />);
    fireEvent.click(screen.getByTestId('variable-picker'));
    fireEvent.change(screen.getByLabelText('Search variables'), { target: { value: 'usage' } });
    expect(screen.getByTestId('variable-picker-item-nodes-check-output-usage')).toBeTruthy();
    expect(screen.queryByTestId('variable-picker-item-run-input-target')).toBeNull();

    fireEvent.change(screen.getByLabelText('Search variables'), { target: { value: 'missing' } });
    expect(screen.getByText('No matching variables')).toBeTruthy();
  });

  it('shows the no-upstream hint when there are no variables', () => {
    render(<VariablePicker groups={[]} mode="template" onInsert={() => {}} />);
    fireEvent.click(screen.getByTestId('variable-picker'));
    expect(screen.getByText('No upstream variables available')).toBeTruthy();
    expect(screen.getByText(/Declare inputs on the Start node/)).toBeTruthy();
  });
});
