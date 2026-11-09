import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../i18n/locales/zh-CN.json';
import enUS from '../../../i18n/locales/en-US.json';
import NodeFormPanel from './NodeFormPanel';
import type { WorkflowNode, WorkflowNodeType } from '../types';

/**
 * 节点配置面板的契约闸门（真机缺陷：UI 建不出能发布的工作流）。
 *
 * 锁三件事：① 每种节点类型渲染出<b>服务端真正读取</b>的字段（`script` / `targets` /
 * `channels` / `messageTemplate` / `expression` / `durationSec` / `title`…）；
 * ② 改字段后 onSave 写进 definition 的 config 键名与服务端一致；
 * ③ targets 的 mode 切换动态显隐对应字段、parameters 键值对能增删。
 *
 * 测试自带 i18n 实例，语言固定 en-US：断言用的就是用户真实看到的英文文案，
 * 顺带校验新键真的同时进了两个 locale（AGENTS.md §4.2 SF-11 / G-19）。
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

/** jsdom 没有 matchMedia，antd 的 Drawer/Form 挂载时会订阅断点（同 modals.test.tsx） */
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
  // Drawer 的尺寸监听走 ResizeObserver，jsdom 没有这个 API（Modal 用不到，所以
  // modals.test.tsx 不需要它；这里补一个只记录不动作的桩）
  if (!('ResizeObserver' in window)) {
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

afterEach(cleanup);

function node(type: WorkflowNodeType, config: Record<string, unknown> = {}): WorkflowNode {
  return {
    key: 'n1',
    type,
    name: 'node under test',
    position: { x: 0, y: 0 },
    config,
    retryMax: 0,
    retryIntervalSec: 5,
    timeoutSec: null,
    onFailure: 'abort',
  };
}

function renderPanel(target: WorkflowNode) {
  const onSave = vi.fn();
  const onRequestDelete = vi.fn();
  render(
    <NodeFormPanel
      node={target}
      open
      onClose={() => {}}
      onSave={onSave}
      onRequestDelete={onRequestDelete}
    />
  );
  return { onSave, onRequestDelete };
}

/** 点保存并把 onSave 收到的 patch 取出来（校验失败时 onSave 不会被调用） */
async function submitAndCapture(onSave: ReturnType<typeof vi.fn>) {
  fireEvent.click(screen.getByRole('button', { name: /Save/ }));
  await waitFor(() => expect(onSave).toHaveBeenCalled());
  return onSave.mock.calls[0][1] as { config: Record<string, unknown> };
}

/**
 * antd Drawer 的内容挂在 document.body，不在容器里。
 * `requiredMark="optional"` 会给非必填字段的 label 追一个 "(optional)" 后缀，
 * 所以这里用正则吃掉它——断言写的仍是用户看到的那段主文案。
 */
const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const byLabel = (name: string) =>
  screen.getByLabelText(new RegExp(`${escapeRegExp(name)}(\\(optional\\))?$`));

/** antd Select 的 aria-label 挂在内部隐藏 input 上，取它的 .ant-select 祖先才能派发 mousedown */
const selectOf = (name: string): HTMLElement => {
  const select = byLabel(name).closest('.ant-select');
  if (!select) throw new Error(`no .ant-select for ${name}`);
  return select as HTMLElement;
};

/** 展开下拉并选中某一项（antd v6 选项是 .ant-select-item-option，title = 展示文案） */
const chooseOption = (field: string, option: string) => {
  fireEvent.mouseDown(selectOf(field));
  fireEvent.click(screen.getByTitle(option));
};

describe('NodeFormPanel — 每种节点类型的字段契约（服务端口径）', () => {
  it('script-exec 渲染 script / targets / parameters / rawParams', () => {
    renderPanel(node('script-exec'));

    expect(byLabel('Script')).toBeTruthy();
    expect(byLabel('Target hosts')).toBeTruthy();
    expect(byLabel('Parameters (slot values)')).toBeTruthy();
    expect(byLabel('Raw parameter declaration')).toBeTruthy();
    // 旧契约的字段名不该再出现
    expect(screen.queryByLabelText('Script content')).toBeNull();
    expect(screen.queryByLabelText('Channel')).toBeNull();
  });

  it('notify 渲染 channels 多选 / messageTemplate / parameters', () => {
    renderPanel(node('notify'));

    expect(byLabel('Channels')).toBeTruthy();
    expect(byLabel('Message template')).toBeTruthy();
    expect(byLabel('Parameters (slot values)')).toBeTruthy();
    expect(screen.queryByLabelText('Message')).toBeNull();
  });

  it('approval 渲染 title / approverRole / timeoutSec / onTimeout / detailTemplate', () => {
    renderPanel(node('approval'));

    expect(byLabel('Approval title')).toBeTruthy();
    expect(byLabel('Approver role')).toBeTruthy();
    expect(byLabel('Approval timeout (s)')).toBeTruthy();
    expect(byLabel('On timeout')).toBeTruthy();
    expect(byLabel('Detail template')).toBeTruthy();
    // mode 的取值必须是服务端认的 approval / human-confirm，不是 confirm / input
    fireEvent.mouseDown(selectOf('Approval mode'));
    // role=option 的无障碍名就是 option 的 value（服务端口径），title 是给人看的文案
    const optionValues = screen.getAllByRole('option').map((option) => option.textContent);
    expect(optionValues).toEqual(['approval', 'human-confirm']);
    // 展示文案由 aria-label 承载（同 ChannelMessageRouter 的 6 个渠道 key 一起进 i18n）
    const optionLabels = screen
      .getAllByRole('option')
      .map((option) => option.getAttribute('aria-label'));
    expect(optionLabels).toEqual(['Full approval', 'Human confirm (light)']);
  });

  it('condition 渲染 expression（带语法提示）', () => {
    renderPanel(node('condition'));

    expect(byLabel('Condition expression')).toBeTruthy();
    expect(
      screen.getByText(/Only paths \/ numbers \/ single-quoted strings/)
    ).toBeTruthy();
  });

  it('wait 渲染 durationSec（1..3600）', () => {
    renderPanel(node('wait'));
    expect(byLabel('Duration')).toBeTruthy();
  });
});

describe('NodeFormPanel — 字段改动写进 definition 的键名与服务端一致', () => {
  it('script-exec：script + targets.mode=explicit 的 agentIds 落进 config', async () => {
    const { onSave } = renderPanel(node('script-exec'));

    fireEvent.change(byLabel('Script'), { target: { value: 'df -h /' } });
    fireEvent.change(byLabel('Agent IDs'), { target: { value: 'agent-1, agent-2' } });

    const patch = await submitAndCapture(onSave);
    expect(patch.config.script).toBe('df -h /');
    expect(patch.config.targets).toEqual({ mode: 'explicit', agentIds: ['agent-1', 'agent-2'] });
    // 错名的旧字段一个都不该出现
    expect(patch.config).not.toHaveProperty('scriptContent');
  });

  it('script-exec：targets.mode=variable 只写 variableRef', async () => {
    const { onSave } = renderPanel(node('script-exec'));

    fireEvent.change(byLabel('Script'), { target: { value: 'echo hi' } });
    chooseOption('Target hosts', 'From upstream variable');
    fireEvent.change(byLabel('Variable reference'), { target: { value: 'nodes.pick.output.hosts' } });

    const patch = await submitAndCapture(onSave);
    expect(patch.config.targets).toEqual({ mode: 'variable', variableRef: 'nodes.pick.output.hosts' });
  });

  it('notify：channels + messageTemplate 落进 config（不是 channel / message）', async () => {
    const { onSave } = renderPanel(node('notify'));

    fireEvent.change(byLabel('Message template'), {
      target: { value: 'disk usage ${nodes.n1.output.usage}%' },
    });

    const patch = await submitAndCapture(onSave);
    expect(patch.config.channels).toEqual(['telegram']);
    expect(patch.config.messageTemplate).toBe('disk usage ${nodes.n1.output.usage}%');
    expect(patch.config).not.toHaveProperty('channel');
    expect(patch.config).not.toHaveProperty('message');
  });

  it('approval：title / approverRole / timeoutSec / onTimeout 落进 config', async () => {
    const { onSave } = renderPanel(node('approval'));

    fireEvent.change(byLabel('Approval title'), { target: { value: '确认清理' } });
    fireEvent.change(byLabel('Approval timeout (s)'), { target: { value: '7200' } });

    const patch = await submitAndCapture(onSave);
    expect(patch.config.title).toBe('确认清理');
    expect(patch.config.approverRole).toBe('ADMIN');
    expect(patch.config.timeoutSec).toBe(7200);
    expect(patch.config.onTimeout).toBe('reject');
  });

  it('condition / wait：expression 与 durationSec 原样落进 config', async () => {
    const condition = renderPanel(node('condition'));
    fireEvent.change(byLabel('Condition expression'), {
      target: { value: 'nodes.n1.output.usage > 90' },
    });
    const conditionPatch = await submitAndCapture(condition.onSave);
    expect(conditionPatch.config.expression).toBe('nodes.n1.output.usage > 90');
    cleanup();

    const wait = renderPanel(node('wait'));
    fireEvent.change(byLabel('Duration'), { target: { value: '120' } });
    const waitPatch = await submitAndCapture(wait.onSave);
    expect(waitPatch.config.durationSec).toBe(120);
  });

  it('缺 script / messageTemplate / title / durationSec 时不调 onSave（必填闸门）', async () => {
    const { onSave } = renderPanel(node('script-exec'));
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await waitFor(() => expect(screen.getByText('Please enter the script')).toBeTruthy());
    expect(onSave).not.toHaveBeenCalled();
  });
});

describe('NodeFormPanel — targets mode 切换时字段动态显隐', () => {
  it('explicit → cluster：agentIds 消失、clusterIds 出现', () => {
    renderPanel(node('script-exec'));

    expect(byLabel('Agent IDs')).toBeTruthy();
    expect(screen.queryByLabelText('Cluster IDs')).toBeNull();

    chooseOption('Target hosts', 'By cluster');

    expect(screen.queryByLabelText('Agent IDs')).toBeNull();
    expect(byLabel('Cluster IDs')).toBeTruthy();
  });

  it('tag / variable 各自只渲染自己的字段', () => {
    renderPanel(node('script-exec'));
    chooseOption('Target hosts', 'By tag');
    expect(byLabel('Tag IDs')).toBeTruthy();
    expect(screen.queryByLabelText('Agent IDs')).toBeNull();

    chooseOption('Target hosts', 'From upstream variable');
    expect(byLabel('Variable reference')).toBeTruthy();
    expect(screen.queryByLabelText('Tag IDs')).toBeNull();
  });

  it('换 mode 后提交只写当前 mode 的键（不留上一个 mode 的残留）', async () => {
    const { onSave } = renderPanel(node('script-exec'));
    fireEvent.change(byLabel('Script'), { target: { value: 'echo hi' } });
    fireEvent.change(byLabel('Agent IDs'), { target: { value: 'agent-1' } });

    chooseOption('Target hosts', 'By tag');
    fireEvent.change(byLabel('Tag IDs'), { target: { value: '3, 7' } });

    const patch = await submitAndCapture(onSave);
    expect(patch.config.targets).toEqual({ mode: 'tag', tagIds: [3, 7] });
    expect(patch.config.targets).not.toHaveProperty('agentIds');
  });
});

describe('NodeFormPanel — parameters 键值对能增删', () => {
  it('添加两行、改名改值、删一行，提交得到对应的 parameters 对象', async () => {
    const { onSave } = renderPanel(node('script-exec'));
    fireEvent.change(byLabel('Script'), { target: { value: 'echo {{A}} {{B}}' } });

    // 初始无参数
    expect(screen.getByText(/No parameters yet/)).toBeTruthy();

    const addRow = () => fireEvent.click(screen.getByRole('button', { name: /Add parameter/ }));

    addRow();
    expect(screen.getAllByLabelText('Parameter name')).toHaveLength(1);
    // 已有空键行时不允许再添加（Record 装不下两个空键，加了会被悄悄吞掉一行）
    expect((screen.getByRole('button', { name: /Add parameter/ }) as HTMLButtonElement).disabled).toBe(true);

    const row = (index: number) => ({
      key: screen.getAllByLabelText('Parameter name')[index] as HTMLInputElement,
      value: screen.getAllByLabelText('Value')[index] as HTMLInputElement,
    });
    fireEvent.change(row(0).key, { target: { value: 'TARGET_DIR' } });
    fireEvent.change(row(0).value, { target: { value: '/var/log' } });

    addRow();
    expect(screen.getAllByLabelText('Parameter name')).toHaveLength(2);
    fireEvent.change(row(1).key, { target: { value: 'THRESHOLD' } });
    fireEvent.change(row(1).value, { target: { value: '${nodes.check.output.usage}' } });

    // 删掉第二行
    fireEvent.click(screen.getAllByLabelText('Remove')[1]);
    expect(screen.getAllByLabelText('Parameter name')).toHaveLength(1);

    const patch = await submitAndCapture(onSave);
    expect(patch.config.parameters).toEqual({ TARGET_DIR: '/var/log' });
  });

  it('notify 的 parameters 同样可编辑，且空键行不会落进 definition', async () => {
    const { onSave } = renderPanel(node('notify'));
    fireEvent.change(byLabel('Message template'), { target: { value: 'done: {{FINAL}}' } });

    fireEvent.click(screen.getByRole('button', { name: /Add parameter/ }));
    const keys = screen.getAllByLabelText('Parameter name') as HTMLInputElement[];
    const values = screen.getAllByLabelText('Value') as HTMLInputElement[];
    fireEvent.change(keys[0], { target: { value: 'FINAL' } });
    fireEvent.change(values[0], { target: { value: '${nodes.use.output}' } });
    expect((values[0] as HTMLInputElement).value).toBe('${nodes.use.output}');

    const patch = await submitAndCapture(onSave);
    expect(patch.config.parameters).toEqual({ FINAL: '${nodes.use.output}' });
  });

  it('script-exec 的 rawParams 选项来自已声明的 parameters', async () => {
    renderPanel(node('script-exec'));
    fireEvent.change(byLabel('Script'), { target: { value: 'find /tmp -name {{PAT:raw}}' } });

    fireEvent.click(screen.getByRole('button', { name: /Add parameter/ }));
    const keys = screen.getAllByLabelText('Parameter name') as HTMLInputElement[];
    fireEvent.change(keys[0], { target: { value: 'PAT' } });
    expect((keys[0] as HTMLInputElement).value).toBe('PAT');

    fireEvent.mouseDown(selectOf('Raw parameter declaration'));
    expect(screen.getByTitle('PAT')).toBeTruthy();
  });

  it('声明 raw 参数而 riskPolicy=block 时给出 RAW_REQUIRES_ALLOW_MEDIUM 提示', () => {
    renderPanel(
      node('script-exec', {
        script: 'find /tmp -name {{PAT:raw}}',
        parameters: { PAT: '*.log' },
        rawParams: ['PAT'],
      })
    );
    expect(screen.getByText(/RAW_REQUIRES_ALLOW_MEDIUM/)).toBeTruthy();
  });
});

describe('NodeFormPanel — 旧草稿字段的兼容读取', () => {
  it('能读回含 targets / parameters / channels 的既有 config 并原样保存', async () => {
    const { onSave } = renderPanel(
      node('script-exec', {
        script: 'df -h /',
        targets: { mode: 'tag', tagIds: [3, 7] },
        parameters: { TARGET_DIR: '/var/log' },
        rawParams: [],
      })
    );

    const patch = await submitAndCapture(onSave);
    expect(patch.config.script).toBe('df -h /');
    expect(patch.config.targets).toEqual({ mode: 'tag', tagIds: [3, 7] });
    expect(patch.config.parameters).toEqual({ TARGET_DIR: '/var/log' });
  });
});
