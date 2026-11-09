import { expect, test } from '@playwright/test';
import { bootstrap, maskVolatile, waitStable } from './helpers';

/**
 * 工作流 M1 视觉基线：列表 / 编辑器（含画布）/ 运行列表 / 运行监控 / 审批中心。
 *
 * 与 routes.spec.ts 同一纪律：先证明页面真的渲染到预期稳态（fixture 数据 + 0 page error），
 * 再截图。暗色 1440 一个 project 铺基线。
 */

const ROUTES: Array<{ name: string; path: string; anchor: (page: import('@playwright/test').Page) => Promise<void> }> = [
  {
    name: 'workflow-list',
    path: '/workflow',
    anchor: async (page) => {
      await expect(page.getByRole('table').first()).toBeVisible();
      await expect(page.getByText('Nginx 巡检').first()).toBeVisible();
    },
  },
  {
    name: 'workflow-editor',
    path: '/workflow/editor/2',
    anchor: async (page) => {
      await expect(page.getByTestId('workflow-canvas')).toBeVisible();
      // React Flow 渲染出 6 个节点 / 6 条边（含 condition 双出把手）
      await expect(page.locator('.react-flow__node')).toHaveCount(6);
      await expect(page.locator('.react-flow__edge')).toHaveCount(6);
      await expect(page.getByTestId('palette-script-exec')).toBeVisible();
    },
  },
  {
    name: 'workflow-run-list',
    path: '/workflow/run',
    anchor: async (page) => {
      await expect(page.getByRole('table').first()).toBeVisible();
      await expect(page.getByText('run-demo-001')).toBeVisible();
    },
  },
  {
    name: 'workflow-monitor',
    path: '/workflow/run/run-demo-001',
    anchor: async (page) => {
      await expect(page.getByTestId('workflow-canvas')).toBeVisible();
      await expect(page.locator('.react-flow__node')).toHaveCount(6);
      // 运行态节点按 nodeRun 状态着色（succeeded 走语义色）
      await expect(page.locator('.wf-node[data-status="succeeded"]').first()).toBeVisible();
      await expect(page.getByTestId('run-log')).toBeVisible();
    },
  },
  {
    name: 'workflow-approvals',
    path: '/workflow/approvals',
    anchor: async (page) => {
      await expect(page.getByRole('table').first()).toBeVisible();
      await expect(page.getByText('确认清理 30 天前的访问日志')).toBeVisible();
    },
  },
];

for (const route of ROUTES) {
  test(`工作流:${route.name}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'dark-1440', 'M1 只生成 dark-1440 基线');

    const pageErrors: Error[] = [];
    page.on('pageerror', (error) => pageErrors.push(error));

    await bootstrap(page, 'dark');
    await page.goto(route.path);
    await waitStable(page);
    await route.anchor(page);
    // 画布动效（fitView 过渡）结束后再截，避免稳态竞态
    await page.waitForTimeout(300);

    expect(pageErrors).toEqual([]);

    await expect(page).toHaveScreenshot(`${route.name}.png`, { mask: maskVolatile(page) });
  });
}


