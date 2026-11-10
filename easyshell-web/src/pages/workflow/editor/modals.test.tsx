import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { message } from 'antd';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../i18n/locales/zh-CN.json';
import enUS from '../../../i18n/locales/en-US.json';
import { TriggerModal, EdgeEditModal } from './modals';
import type { WorkflowDefinition, WorkflowNode } from '../types';

/**
 * 触发弹窗的回归闸门（真机 E2E 缺陷：无入参变量的工作流在 UI 上点"触发"毫无反应）：
 * 1. `validateFields` 的 reject 曾被 `.then()` 裸接成 unhandled rejection，用户看不到任何反馈；
 * 2. 绑了 `form` 的 `<Form>` 只在有变量时渲染，无变量时 form 实例根本没挂上任何 Form；
 * 3. 幂等键 Input 是受控组件且没有 `name`，`required` 红星永不进校验——红星是误导。
 *
 * 测试自带一份 i18n 实例：不依赖 localStorage / navigator，语言固定为 en-US，
 * 断言用的就是用户真实看到的英文文案（顺带校验新键真的进了两个 locale）。
 */
i18next.use(initReactI18next).init({
  resources: {
    'zh-CN': { translation: zhCN },
    'en-US': { translation: enUS },
  },
  lng: 'en-US',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

/** jsdom 没有 matchMedia，antd 的 Modal/Form 挂载时会订阅断点（同 density.test.tsx） */
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
});

/**
 * @testing-library 不会自动卸载（没开 globals:true 的 setupFiles），逐个用例手工清理。
 *
 * `cleanup()` 只拆 DOM，不会等 React 的 scheduler 排空：scheduler 用 `setImmediate` 跑
 * `performWorkUntilDeadline`，任何没被 await 掉的 state 更新都会把一个宏任务留在队列里。
 * 这个回调跑在测试文件结束、jsdom 环境被拆掉之后，此时 `window` 已是 undefined，于是 vitest
 * 在测试全绿的情况下仍报一条 unhandled error：
 *
 *   ReferenceError: window is not defined
 *    ❯ react-dom-client.development.js
 *    ❯ Immediate.performWorkUntilDeadline scheduler.development.js
 *
 * CI 的时序（更快或更慢都行）决定它是否可见，所以本地复现不了。等一个宏任务让这些回调在
 * 环境还活着的时候跑完，unhandled error 就无处可生。
 */
afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

const emptyDefinition: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [],
  edges: [],
};

const definitionWithRequiredVariable: WorkflowDefinition = {
  ...emptyDefinition,
  variables: {
    target: { type: 'string', required: true, description: 'deploy target' },
  },
};

const definitionWithOptionalVariable: WorkflowDefinition = {
  ...emptyDefinition,
  variables: {
    target: { type: 'string', required: false, default: 'staging' },
  },
};

const renderTrigger = (
  definition: WorkflowDefinition,
  onSubmit: (inputs: Record<string, unknown>, idempotencyKey: string) => void,
) =>
  render(
    <TriggerModal
      open
      definition={definition}
      workflowName="deploy-flow"
      loading={false}
      onCancel={() => {}}
      onSubmit={onSubmit}
    />,
  );

const clickOk = () => fireEvent.click(screen.getByRole('button', { name: 'Trigger' }));

/**
 * 幂等键输入框：antd 用 Form.Item 的 name 生成 id，故输入框 id 就是 `__idem`。
 * 刷新按钮是纯图标按钮（无可及名称），取 Compact 里的那个 .ant-btn。
 */
const idempotencyInput = () =>
  document.querySelector<HTMLInputElement>('input[id="__idem"]')!;

const refreshButton = () =>
  document.querySelector<HTMLButtonElement>('.ant-space-compact .ant-btn')!;

describe('TriggerModal', () => {
  it('无入参变量时点"触发"直接放行：onSubmit 收到空 inputs 与一把幂等键', async () => {
    const onSubmit = vi.fn();
    renderTrigger(emptyDefinition, onSubmit);

    clickOk();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const [inputs, key] = onSubmit.mock.calls[0];
    expect(inputs).toEqual({});
    expect(key).toMatch(/\S/);
  });

  it('无入参变量时也不会留下 unhandled rejection（原 .then() 无 .catch() 的回归）', async () => {
    const rejections: PromiseRejectionEvent[] = [];
    const onUnhandled = (event: PromiseRejectionEvent) => {
      rejections.push(event);
      event.preventDefault();
    };
    window.addEventListener('unhandledrejection', onUnhandled);

    try {
      const onSubmit = vi.fn();
      renderTrigger(emptyDefinition, onSubmit);
      clickOk();
      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
      // 让 microtask 队列排空，unhandledrejection 在本轮事件循环末尾才派发
      await new Promise((resolve) => setTimeout(resolve, 0));
    } finally {
      window.removeEventListener('unhandledrejection', onUnhandled);
    }

    expect(rejections).toEqual([]);
  });

  it('必填变量未填时点"触发"：不提交，且给出可见的错误提示', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const onSubmit = vi.fn();
    renderTrigger(definitionWithRequiredVariable, onSubmit);

    clickOk();

    // 等校验跑完：既不能提交，也要看到 message.error 的反馈（mock 只观测不改渲染，
    // 所以 toast 文案仍会进 DOM，下面两条断言互为备份）
    await waitFor(() => expect(errorSpy).toHaveBeenCalled());
    expect(onSubmit).not.toHaveBeenCalled();
    expect(errorSpy.mock.calls[0][0]).toBe('Please fix the form errors and try again');
    await waitFor(() =>
      expect(document.body.textContent).toContain('Please fix the form errors and try again'),
    );
    errorSpy.mockRestore();
  });

  it('幂等键被清空时点"触发"：不提交，且报错（原红星永不进校验的回归）', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const onSubmit = vi.fn();
    renderTrigger(emptyDefinition, onSubmit);

    const input = idempotencyInput();
    expect(input).toBeTruthy();
    fireEvent.change(input, { target: { value: '' } });
    clickOk();

    await waitFor(() => expect(errorSpy).toHaveBeenCalled());
    expect(onSubmit).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('幂等键的值正确传透给 onSubmit：改输入框 → 第二参数就是新值', async () => {
    const onSubmit = vi.fn();
    renderTrigger(emptyDefinition, onSubmit);

    const input = idempotencyInput();
    fireEvent.change(input, { target: { value: 'my-idem-key-42' } });
    clickOk();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][1]).toBe('my-idem-key-42');
    expect(onSubmit.mock.calls[0][0]).toEqual({});
  });

  it('点刷新按钮换一把新幂等键，随后提交传透的是新值', async () => {
    const onSubmit = vi.fn();
    renderTrigger(emptyDefinition, onSubmit);

    const input = idempotencyInput();
    const before = input.value;
    expect(before).toMatch(/\S/);

    fireEvent.click(refreshButton());
    await waitFor(() => expect(input.value).not.toBe(before));

    clickOk();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][1]).toBe(input.value);
    expect(onSubmit.mock.calls[0][1]).not.toBe(before);
  });

  it('变量值一起传透：onSubmit 的 inputs 带上填了的入参', async () => {
    const onSubmit = vi.fn();
    renderTrigger(definitionWithOptionalVariable, onSubmit);

    // 弹窗里有两个 Input（变量 + 幂等键），按 label 关联的 id 取变量那个
    const variableInput = document.querySelector<HTMLInputElement>('input[id="target"]')!;
    fireEvent.change(variableInput, { target: { value: 'prod-cluster' } });
    clickOk();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toEqual({ target: 'prod-cluster' });
  });
});

/* ── EdgeEditModal：condition 边由 sourceHandle 门控 ─────────────── */

const edgeNode = (key: string, type: WorkflowNode['type']): WorkflowNode => ({
  key,
  type,
  name: key,
  position: { x: 0, y: 0 },
  config: {},
});

describe('EdgeEditModal', () => {
  it('condition 来源：隐藏表达式输入，展示 IF/ELSE 分支，并在保存时清掉旧 condition', () => {
    const onSave = vi.fn();
    render(
      <EdgeEditModal
        open
        edge={{ id: 'cond:false->end', from: 'cond', to: 'end', sourceHandle: 'false', condition: 'legacy expr' }}
        nodes={[edgeNode('cond', 'condition'), edgeNode('end', 'end')]}
        onCancel={() => {}}
        onSave={onSave}
      />
    );

    expect(screen.getByText('IF / ELSE is gated by the outgoing handle')).toBeTruthy();
    expect(screen.getByText('ELSE')).toBeTruthy();
    expect(screen.getByText('false')).toBeTruthy();
    expect(screen.queryByLabelText('Condition (empty = always true)')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSave).toHaveBeenCalledWith('cond:false->end', { condition: undefined, onUpstreamFailure: 'skip' });
  });

  it('普通来源：继续保留 condition 表达式编辑', async () => {
    const onSave = vi.fn();
    render(
      <EdgeEditModal
        open
        edge={{ id: 'n1:out->end', from: 'n1', to: 'end', sourceHandle: 'out' }}
        nodes={[edgeNode('n1', 'script-exec'), edgeNode('end', 'end')]}
        onCancel={() => {}}
        onSave={onSave}
      />
    );

    const input = screen.getByLabelText('Condition (empty = always true)') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'nodes.n1.output.count > 0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][1].condition).toBe('nodes.n1.output.count > 0');
  });
});
