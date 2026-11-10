import { describe, expect, it, vi, beforeAll, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../../i18n/locales/zh-CN.json';
import enUS from '../../../../i18n/locales/en-US.json';
import NodeFormPanel from '../NodeFormPanel';
import type { WorkflowDefinition, WorkflowNode, WorkflowNodeType } from '../../types';
import { invalidVariableNames, rowsToVariables } from './startVariablesModel';

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

function node(type: WorkflowNodeType, config: Record<string, unknown> = {}, extra: Partial<WorkflowNode> = {}): WorkflowNode {
  return {
    key: type === 'start' ? 'start' : type === 'end' ? 'end' : 'n1',
    type,
    name: 'node under test',
    position: { x: 0, y: 0 },
    config,
    retryMax: 0,
    retryIntervalSec: 5,
    timeoutSec: null,
    onFailure: 'abort',
    ...extra,
  };
}

function definitionFor(target: WorkflowNode, variables: WorkflowDefinition['variables'] = {}): WorkflowDefinition {
  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables,
    nodes: [target],
    edges: [],
  };
}

async function submit(onSave: ReturnType<typeof vi.fn>) {
  fireEvent.click(screen.getByRole('button', { name: /Save/ }));
  await waitFor(() => expect(onSave).toHaveBeenCalled());
  return onSave.mock.calls[0][1] as { config: Record<string, unknown> };
}

describe('startVariablesModel', () => {
  it('maps rows to definition.variables and rejects invalid/duplicate names', () => {
    const rows = [
      { name: 'targets', type: 'json' as const, required: true, default: ['agent-1'], description: 'hosts' },
      { name: '9bad', type: 'string' as const, required: false, default: '', description: '' },
      { name: 'targets', type: 'string' as const, required: false, default: '', description: '' },
    ];
    expect(rowsToVariables(rows)).toEqual({
      targets: { type: 'json', required: true, default: ['agent-1'], description: 'hosts' },
    });
    expect(invalidVariableNames(rows)).toEqual(['9bad', 'targets']);
  });
});

describe('Start variables editor', () => {
  it('renders the editor, saves definition.variables via onSaveMeta, and blocks deletion', async () => {
    const onSave = vi.fn();
    const onSaveMeta = vi.fn();
    const target = node('start');
    render(
      <NodeFormPanel
        node={target}
        definition={definitionFor(target, { env: { type: 'string', required: true } })}
        onSave={onSave}
        onSaveMeta={onSaveMeta}
        onRequestDelete={() => {}}
      />
    );

    expect(screen.getByTestId('start-variables-editor')).toBeTruthy();
    expect((screen.getByRole('button', { name: /Delete/ }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: /Add input/ }));
    const names = screen.getAllByLabelText('Name') as HTMLInputElement[];
    fireEvent.change(names[1], { target: { value: 'threshold' } });
    const descriptions = screen.getAllByLabelText('Description') as HTMLInputElement[];
    fireEvent.change(descriptions[1], { target: { value: 'Usage percent threshold' } });
    fireEvent.click(screen.getAllByRole('switch', { name: 'Required' })[1]);

    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await waitFor(() => expect(onSaveMeta).toHaveBeenCalled());
    expect(onSaveMeta.mock.calls[0][0]).toEqual({
      env: { type: 'string', required: true },
      threshold: { type: 'string', required: true, description: 'Usage percent threshold' },
    });
  });

  it('does not save invalid input names', async () => {
    const onSave = vi.fn();
    const onSaveMeta = vi.fn();
    const target = node('start');
    render(
      <NodeFormPanel
        node={target}
        definition={definitionFor(target)}
        onSave={onSave}
        onSaveMeta={onSaveMeta}
        onRequestDelete={() => {}}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Add input/ }));
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '9bad' } });
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onSaveMeta).not.toHaveBeenCalled();
    expect(screen.getByText(/Names must match/)).toBeTruthy();
  });
});

describe('Transform mappings editor', () => {
  it('saves config.mappings with name/value rows', async () => {
    const onSave = vi.fn();
    const target = node('transform', { mappings: [] });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('transform-mappings-editor')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Add mapping/ }));
    fireEvent.change(screen.getByLabelText('Field name'), { target: { value: 'usage' } });
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: '${nodes.check.output.usage}' } });

    const patch = await submit(onSave);
    expect(patch.config.mappings).toEqual([{ name: 'usage', value: '${nodes.check.output.usage}' }]);
  });
});

describe('End outputs editor', () => {
  it('saves config.outputs with name/value rows and supports the variable picker', async () => {
    const onSave = vi.fn();
    const target = node('end', { outputs: [] });
    const source = node('script-exec', { script: 'df -h' }, { key: 'check', name: 'Check' });
    const def: WorkflowDefinition = {
      ...definitionFor(target),
      nodes: [source, target],
      edges: [{ id: 'check:out->end', from: 'check', to: 'end' }],
    };
    render(<NodeFormPanel node={target} definition={def} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('end-outputs-editor')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Add output/ }));
    fireEvent.change(screen.getByLabelText('Output name'), { target: { value: 'summary' } });
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: '${nodes.check.output}' } });

    const patch = await submit(onSave);
    expect(patch.config.outputs).toEqual([{ name: 'summary', value: '${nodes.check.output}' }]);
  });
});

describe('AI analyze fields', () => {
  it('renders instructions/input/maxInputChars and the no-execution warning', async () => {
    const onSave = vi.fn();
    const target = node('ai-analyze', { instructions: '', input: '', maxInputChars: 8000 });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('ai-analyze-fields')).toBeTruthy();
    expect(screen.getByText(/cannot execute commands or dispatch tasks/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Instructions'), { target: { value: 'Find risky mounts' } });
    fireEvent.change(screen.getByLabelText(/Input template/), { target: { value: '${run.input.targets}' } });
    fireEvent.change(screen.getByLabelText(/Max input chars/), { target: { value: '4000' } });

    const patch = await submit(onSave);
    expect(patch.config.instructions).toBe('Find risky mounts');
    expect(patch.config.input).toBe('${run.input.targets}');
    expect(patch.config.maxInputChars).toBe(4000);
  });
});
