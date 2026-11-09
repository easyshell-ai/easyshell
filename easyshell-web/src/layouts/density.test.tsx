import { describe, it, expect, beforeAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ConfigProvider, Table } from 'antd';
import DensityScope from './density';
import {
  DENSITY,
  SIDEBAR_WIDTH,
  SIDEBAR_COLLAPSED_WIDTH,
  HEADER_HEIGHT,
  CONTENT_PADDING,
  CONTENT_MAX_WIDTH,
  MOBILE_DRAWER_WIDTH,
  ICON_BUTTON_SIZE,
} from './layoutConstants';
import { darkTheme } from '../theme/themeConfig';

/**
 * jsdom 没有 matchMedia，而 antd 的 Grid（Row/Col）一挂载就订阅断点。
 * 不补这个桩，任何渲染 antd 布局组件的测试都会以 "window.matchMedia is not a function" 崩，
 * 与断言无关——所以放在 beforeAll 里一次性补掉。
 */
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
 * 布局框架的机器闸门（设计文档 §4.10.4 / §4.10.2）：
 * - 尺寸常量就是文档写的那些数，改 token / 改这里必须同步，否则这里红；
 * - 密度 compact 档必须真的落到 AntD emit 出来的 `--es-*` CSS 变量上——证明 §4.10.2 规定的
 *   "嵌套 ConfigProvider" 这条路在 AntD 6 的 cssVar 模式下有效，而不是写了一组没人用的数字。
 *
 * 外层必须包 ConfigProvider theme={darkTheme}：cssVar 的 prefix/key 由最外层 ConfigProvider
 * 决定，不包的话 antd 回落默认主题，emit 出来的是 `--ant-*`（第一版测试就这么错过一次）。
 */
const withAppTheme = (ui: React.ReactNode) => (
  <ConfigProvider theme={darkTheme}>{ui}</ConfigProvider>
);

const injectedVars = (): string =>
  Array.from(document.querySelectorAll('style'))
    .map((s) => s.textContent ?? '')
    .join('\n');

describe('布局框架常量', () => {
  it('整体框架尺寸与 §4.10.4 逐字一致', () => {
    expect(SIDEBAR_WIDTH).toBe(216);
    expect(SIDEBAR_COLLAPSED_WIDTH).toBe(56);
    expect(HEADER_HEIGHT).toBe(52);
    expect(CONTENT_PADDING).toBe(20);
    expect(CONTENT_MAX_WIDTH).toBe(1600);
    expect(MOBILE_DRAWER_WIDTH).toBe(240);
    expect(ICON_BUTTON_SIZE).toBe(28);
  });

  it('default 密度档与 §3.8.4 实配一致（8/12/13 与 controlHeight 32）', () => {
    expect(DENSITY.default.token?.controlHeight).toBe(32);
    expect(DENSITY.default.token?.controlHeightSM).toBe(28);
    expect(DENSITY.default.token?.fontSize).toBe(14);
    expect(DENSITY.default.components?.Table).toMatchObject({
      cellPaddingBlock: 8,
      cellPaddingInline: 12,
      cellFontSize: 13,
    });
    expect(DENSITY.default.components?.Menu?.itemHeight).toBe(34);
  });

  it('compact 密度档与 §4.10.2 的 compact 列一致', () => {
    expect(DENSITY.compact.token?.controlHeight).toBe(28);
    expect(DENSITY.compact.token?.controlHeightSM).toBe(24);
    expect(DENSITY.compact.token?.fontSize).toBe(13);
    expect(DENSITY.compact.token?.paddingContentHorizontalLG).toBe(16);
    expect(DENSITY.compact.components?.Table).toMatchObject({
      cellPaddingBlock: 4,
      cellPaddingInline: 8,
      cellFontSize: 12,
    });
    expect(DENSITY.compact.components?.Menu?.itemHeight).toBe(30);
  });
});

describe('DensityScope', () => {
  it('默认档把 Table 单元格 token 注册成 8px/12px/13px', () => {
    render(
      withAppTheme(
        <DensityScope>
          <Table rowKey="id" dataSource={[]} columns={[{ title: 'x', dataIndex: 'x' }]} />
        </DensityScope>,
      ),
    );
    const vars = injectedVars();
    expect(vars).toContain('--es-table-cell-padding-block:8px');
    expect(vars).toContain('--es-table-cell-padding-inline:12px');
    expect(vars).toContain('--es-table-cell-font-size:13px');
  });

  it('compact 档把同一批 token 重新注册成 4px/8px/12px', () => {
    render(
      withAppTheme(
        <DensityScope compact>
          <Table rowKey="id" dataSource={[]} columns={[{ title: 'x', dataIndex: 'x' }]} />
        </DensityScope>,
      ),
    );
    const vars = injectedVars();
    expect(vars).toContain('--es-table-cell-padding-block:4px');
    expect(vars).toContain('--es-table-cell-padding-inline:8px');
    expect(vars).toContain('--es-table-cell-font-size:12px');
  });

  it('compact 档同时把控件高降到 28px、正文字号降到 13px', () => {
    render(
      withAppTheme(
        <DensityScope compact>
          <button type="button">ok</button>
        </DensityScope>,
      ),
    );
    const vars = injectedVars();
    expect(vars).toContain('--es-control-height:28px');
    expect(vars).toContain('--es-control-height-sm:24px');
    expect(vars).toContain('--es-font-size:13px');
  });

  it('children 原样渲染，compact 不吞内容', () => {
    render(
      withAppTheme(
        <DensityScope compact>
          <span>紧凑区</span>
        </DensityScope>,
      ),
    );
    expect(screen.getByText('紧凑区')).toBeTruthy();
  });
});
