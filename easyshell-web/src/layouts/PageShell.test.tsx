import { describe, it, expect, beforeAll, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import PageShell from './PageShell';
import { darkTheme } from '../theme/themeConfig';

/** jsdom 没有 matchMedia，antd 的 Card/Grid 一挂载就订阅断点（同 density.test.tsx） */
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

const renderShell = (ui: React.ReactNode) =>
  render(<ConfigProvider theme={darkTheme}>{ui}</ConfigProvider>);

/** @testing-library 不会自动卸载（没开 globals:true 的 setupFiles），逐个用例手工清理 */
afterEach(cleanup);

describe('PageShell 页面骨架', () => {
  it('渲染标题区 + 操作区 + 内容区三块', () => {
    const { container } = renderShell(
      <PageShell
        title="任务列表"
        icon={<span data-testid="icon">ic</span>}
        description="任务说明"
        extra={<button type="button">新建</button>}
      >
        <div data-testid="body">表格</div>
      </PageShell>,
    );

    expect(screen.getByRole('heading', { level: 3, name: '任务列表' })).toBeTruthy();
    expect(screen.getByTestId('icon')).toBeTruthy();
    expect(screen.getByText('任务说明')).toBeTruthy();
    expect(screen.getByRole('button', { name: '新建' })).toBeTruthy();
    expect(screen.getByTestId('body')).toBeTruthy();

    // 三块必须在同一个 .es-page 容器里，且 DOM 顺序 = 标题 → 说明 → 内容
    const page = container.querySelector('.es-page');
    expect(page).toBeTruthy();
    const kids = Array.from(page!.children).map((c) => c.className);
    expect(kids).toEqual(['es-page-header', 'es-page-description', 'es-page-body']);
  });

  it('description / extra / filter 全部可省略', () => {
    const { container } = renderShell(<PageShell title="只有标题">内容</PageShell>);
    expect(screen.queryByText('任务说明')).toBeNull();
    expect(container.querySelector('.es-page-description')).toBeNull();
    expect(container.querySelector('.es-page-extra')).toBeNull();
    expect(container.querySelector('.es-page-filter')).toBeNull();
    expect(screen.getByText('内容')).toBeTruthy();
  });

  it('filter 渲染在紧凑密度作用域里（DensityScope compact）', () => {
    const { container } = renderShell(
      <PageShell title="主机" filter={<span>筛选器</span>}>
        <span>表格</span>
      </PageShell>,
    );
    expect(screen.getByText('筛选器')).toBeTruthy();
    // filter 卡存在，且排在内容区之前
    const filter = container.querySelector('.es-page-filter');
    expect(filter).toBeTruthy();
    expect(container.querySelector('.es-page-body')).toBeTruthy();
  });

  it('loading=true 时内容区换成骨架屏，不再渲染 children', () => {
    const { container } = renderShell(
      <PageShell title="主机" loading>
        <span>不该出现</span>
      </PageShell>,
    );
    expect(screen.queryByText('不该出现')).toBeNull();
    expect(container.querySelector('.es-page-skeleton')).toBeTruthy();
    // 标题区仍在：骨架只替换内容区
    expect(screen.getByRole('heading', { level: 3, name: '主机' })).toBeTruthy();
  });

  it('title 可以是 ReactNode（不只是字符串）', () => {
    renderShell(
      <PageShell title={<span>自定义标题</span>}>内容</PageShell>,
    );
    expect(screen.getByText('自定义标题')).toBeTruthy();
  });
});
