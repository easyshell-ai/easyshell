import { describe, expect, it, vi, beforeAll, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../../i18n/locales/zh-CN.json';
import enUS from '../../../../i18n/locales/en-US.json';
import NodeFormPanel from '../NodeFormPanel';
import type { WorkflowDefinition, WorkflowNode, WorkflowNodeType } from '../../types';
import { invalidVariableNames, rowsToVariables } from './startVariablesModel';

vi.mock('@uiw/react-codemirror', () => ({
  default: ({ value, onChange, id, 'aria-label': label }: {
    value: string; onChange: (value: string) => void; id?: string; 'aria-label': string;
  }) => <textarea id={id} aria-label={label} value={value} onChange={(event) => onChange(event.target.value)} />,
}));

// LLM / classifier 表单会拉 AI 配置；测试里固定返回，避免真实 XHR 在 jsdom 拆环境后回调
vi.mock('../../../../api/ai', () => ({
  getAiConfig: vi.fn(async () => ({
    code: 200,
    message: 'ok',
    data: { providers: { openai: {}, anthropic: {} } },
  })),
  getProviderModels: vi.fn(async () => ({
    code: 200,
    message: 'ok',
    data: [
      { id: 'gpt-4o', name: 'GPT-4o' },
      { id: 'gpt-4o-mini', name: 'GPT-4o mini' },
    ],
  })),
}));

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
      {
        name: 'targets',
        label: 'Target hosts',
        type: 'json' as const,
        required: true,
        options: [],
        default: ['agent-1'],
        description: 'hosts',
      },
      { name: '9bad', label: '', type: 'string' as const, required: false, options: [], default: '', description: '' },
      { name: 'targets', label: '', type: 'string' as const, required: false, options: [], default: '', description: '' },
    ];
    expect(rowsToVariables(rows)).toEqual({
      targets: { type: 'json', label: 'Target hosts', required: true, default: ['agent-1'], description: 'hosts' },
    });
    expect(invalidVariableNames(rows)).toEqual(['9bad', 'targets']);
  });

  it('keeps string options only for string variables and normalizes duplicates', () => {
    const rows = [
      {
        name: 'env',
        label: 'Environment',
        type: 'string' as const,
        required: true,
        options: ['prod', ' prod ', 'staging', ''],
        default: 'prod',
        description: '',
      },
      {
        name: 'count',
        label: '',
        type: 'number' as const,
        required: false,
        options: ['ignored'],
        default: 3,
        description: '',
      },
    ];
    expect(rowsToVariables(rows)).toEqual({
      env: { type: 'string', label: 'Environment', required: true, options: ['prod', 'staging'], default: 'prod' },
      count: { type: 'number', default: 3 },
    });
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

  it('edits label, string options and type-aware default', async () => {
    const onSave = vi.fn();
    const onSaveMeta = vi.fn();
    const target = node('start');
    render(
      <NodeFormPanel
        node={target}
        definition={definitionFor(target, {
          env: { type: 'string', label: 'Environment', required: true, options: ['prod', 'staging'] },
        })}
        onSave={onSave}
        onSaveMeta={onSaveMeta}
        onRequestDelete={() => {}}
      />
    );

    expect((screen.getByTestId('start-label') as HTMLInputElement).value).toBe('Environment');
    expect(screen.getByTestId('start-options')).toBeTruthy();
    fireEvent.change(screen.getByTestId('start-label'), { target: { value: 'Deploy environment' } });

    // 初始有 2 个枚举值，添加第 3 个并填写
    fireEvent.click(screen.getByRole('button', { name: /Add option/ }));
    const optionInputs = screen.getAllByLabelText('Option value') as HTMLInputElement[];
    expect(optionInputs).toHaveLength(3);
    fireEvent.change(optionInputs[2], { target: { value: 'dev' } });

    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await waitFor(() => expect(onSaveMeta).toHaveBeenCalled());
    expect(onSaveMeta.mock.calls[0][0]).toEqual({
      env: {
        type: 'string',
        label: 'Deploy environment',
        required: true,
        options: ['prod', 'staging', 'dev'],
      },
    });
  });

  it('hides the options editor for non-string variables', () => {
    const target = node('start');
    render(
      <NodeFormPanel
        node={target}
        definition={definitionFor(target, {
          count: { type: 'number', label: 'Count', options: ['ignored'] },
        })}
        onSave={() => {}}
        onSaveMeta={() => {}}
        onRequestDelete={() => {}}
      />
    );
    expect(screen.queryByTestId('start-options')).toBeNull();
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

describe('Condition builder', () => {
  it('composes the expression from structured fields and saves it', async () => {
    const onSave = vi.fn();
    const target = node('condition', { expression: 'nodes.n1.output.usage > 80' });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('condition-builder')).toBeTruthy();
    const left = screen.getByLabelText('Left value') as HTMLInputElement;
    const right = screen.getByLabelText('Right value (literal or variable)') as HTMLInputElement;
    expect(left.value).toBe('nodes.n1.output.usage');
    expect(right.value).toBe('80');

    fireEvent.change(right, { target: { value: '90' } });
    expect((screen.getByLabelText('Condition expression') as HTMLTextAreaElement).value).toBe(
      'nodes.n1.output.usage > 90'
    );

    const patch = await submit(onSave);
    expect(patch.config.expression).toBe('nodes.n1.output.usage > 90');
  });

  it('keeps the advanced raw textarea as the source of truth', async () => {
    const onSave = vi.fn();
    const target = node('condition', { expression: 'nodes.a.output.x > 1' });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    const raw = screen.getByLabelText('Condition expression') as HTMLTextAreaElement;
    const compound = 'nodes.a.output.x > 1 && nodes.b.output.y < 2';
    fireEvent.change(raw, { target: { value: compound } });
    expect(screen.getByText(/contains compound conditions/)).toBeTruthy();

    const patch = await submit(onSave);
    expect(patch.config.expression).toBe(compound);
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
    expect(patch.config.outputMode).toBe('text');
  });

  it('supports structured JSON output with schema guidance', async () => {
    const onSave = vi.fn();
    const target = node('ai-analyze', {
      instructions: 'Return a verdict',
      input: '',
      maxInputChars: 8000,
      outputMode: 'json',
      jsonSchema: '{"type":"object"}',
    });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    const schema = screen.getByLabelText('JSON Schema guidance') as HTMLTextAreaElement;
    expect(schema.value).toBe('{"type":"object"}');
    fireEvent.change(schema, { target: { value: '{"type":"object","properties":{"verdict":{"type":"string"}}}' } });

    const patch = await submit(onSave);
    expect(patch.config.outputMode).toBe('json');
    expect(patch.config.jsonSchema).toContain('"verdict"');
  });
});

/* ── Workflow 2.1 新节点：llm / classifier / code ─────────────────── */

describe('LLM node fields', () => {
  it('renders provider/model prompts and writes text mode config', async () => {
    const onSave = vi.fn();
    const target = node('llm', {});
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('llm-fields')).toBeTruthy();
    expect(screen.getByText(/cannot be used as raw script parameters/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('User prompt'), { target: { value: 'Analyze ${nodes.check.output}' } });
    fireEvent.change(screen.getByLabelText('System prompt'), { target: { value: 'You are an SRE.' } });
    fireEvent.change(screen.getByLabelText('Temperature'), { target: { value: '0.2' } });
    fireEvent.change(screen.getByLabelText('Max input chars'), { target: { value: '6000' } });

    const patch = await submit(onSave);
    expect(patch.config).toMatchObject({
      provider: null,
      model: null,
      systemPrompt: 'You are an SRE.',
      userPrompt: 'Analyze ${nodes.check.output}',
      outputMode: 'text',
      temperature: 0.2,
      maxInputChars: 6000,
    });
  });

  it('shows the JSON schema only in json mode and persists it', async () => {
    const onSave = vi.fn();
    const target = node('llm', { userPrompt: 'Return JSON' });
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.queryByLabelText('JSON Schema')).toBeNull();
    fireEvent.mouseDown(
      screen.getByLabelText('Output mode').closest('.ant-select') as HTMLElement
    );
    fireEvent.click(screen.getByTitle('Structured JSON (json)'));
    const schema = await screen.findByLabelText('JSON Schema');
    fireEvent.change(schema, { target: { value: '{"type":"object"}' } });

    const patch = await submit(onSave);
    expect(patch.config.outputMode).toBe('json');
    expect(patch.config.jsonSchema).toBe('{"type":"object"}');
  });
});

describe('Classifier node fields', () => {
  it('renders classes editor and writes class handles config', async () => {
    const onSave = vi.fn();
    const target = node('classifier', {});
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('classifier-fields')).toBeTruthy();
    expect(screen.getByTestId('classifier-classes')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Input template'), {
      target: { value: '${nodes.check.output}' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Add class/ }));
    fireEvent.change(screen.getByLabelText('Class name'), { target: { value: 'disk' } });
    fireEvent.change(screen.getByLabelText('Class description'), { target: { value: 'Disk issues' } });
    fireEvent.click(screen.getByRole('button', { name: /Add class/ }));
    const names = screen.getAllByLabelText('Class name') as HTMLInputElement[];
    fireEvent.change(names[1], { target: { value: 'network' } });
    fireEvent.change(screen.getByLabelText('Instructions'), {
      target: { value: 'Classify into one class.' },
    });

    const patch = await submit(onSave);
    expect(patch.config).toMatchObject({
      input: '${nodes.check.output}',
      instructions: 'Classify into one class.',
      provider: null,
      model: null,
    });
    expect(patch.config.classes).toEqual([
      { name: 'disk', description: 'Disk issues' },
      { name: 'network' },
    ]);
  });

  it('blocks saving without a valid class', async () => {
    const onSave = vi.fn();
    const target = node('classifier', {});
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    fireEvent.change(screen.getByLabelText('Input template'), {
      target: { value: '${nodes.check.output}' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onSave).not.toHaveBeenCalled();
  });
});

describe('Code node fields', () => {
  it('renders language/code/inputs/timeout, inserts the main skeleton and saves', async () => {
    const onSave = vi.fn();
    const target = node('code', {});
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    expect(screen.getByTestId('code-fields')).toBeTruthy();
    expect(screen.getByText('Running code requires a configured isolated execution service. If the service is not ready, execution is refused; code never runs locally on the server.')).toBeTruthy();
    expect(screen.getByText('The sample runs in a real data sandbox. It does not dispatch host tasks or advance the workflow. Do not enter sensitive values.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Run sample' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /Insert main skeleton/ }));
    const code = screen.getByLabelText('Code') as HTMLTextAreaElement;
    expect(code.value).toContain('function main(input)');

    fireEvent.click(screen.getByRole('button', { name: /Add input/ }));
    fireEvent.change(screen.getByLabelText('Input name'), { target: { value: 'a' } });
    fireEvent.change(screen.getByLabelText('Input value'), { target: { value: '${nodes.n1.output.count}' } });
    fireEvent.change(screen.getByLabelText('Timeout (s)'), { target: { value: '4' } });

    const patch = await submit(onSave);
    expect(patch.config.language).toBe('javascript');
    expect(patch.config.code).toContain('function main(input)');
    expect(patch.config.inputs).toEqual({ a: '${nodes.n1.output.count}' });
    expect(patch.config.timeoutSec).toBe(4);
  });

  it('switches to Python and inserts the Python skeleton', async () => {
    const onSave = vi.fn();
    const target = node('code', {});
    render(<NodeFormPanel node={target} definition={definitionFor(target)} onSave={onSave} onRequestDelete={() => {}} />);

    fireEvent.mouseDown(screen.getByLabelText('Language').closest('.ant-select') as HTMLElement);
    fireEvent.click(screen.getByTitle('Python'));
    fireEvent.click(screen.getByRole('button', { name: /Insert main skeleton/ }));
    expect((screen.getByLabelText('Code') as HTMLTextAreaElement).value).toContain('def main(input):');
    const patch = await submit(onSave);
    expect(patch.config.language).toBe('python');
    expect(patch.config.code).toContain('def main(input):');
  });
});
