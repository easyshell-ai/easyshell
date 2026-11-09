import { expect, test } from '@playwright/test';
import { bootstrap, maskVolatile, waitStable } from './helpers';

/**
 * 编辑器冒烟（M1 验收项）：加载 → 画布渲染出 definition 的节点与边 → 调色板可用 →
 * 定义 JSON 与画布同源 → 0 page error。
 *
 * 交互逻辑（拖入/移动/连线/成环拒/删除确认/撤销重做/复制粘贴 key 重生成/对齐/自动布局/
 * 防抖保存/localStorage 脱敏）由 Vitest 单元测试覆盖：graph.test.ts、HistoryStack.test.ts、
 * clipboard.test.ts、alignment.test.ts、useWorkflowEditor.test.ts、draftStorage.test.ts、
 * sensitiveFilter.test.ts。Playwright 侧另有 workflow-perf.spec.ts 的拖拽帧时间门槛。
 */

test('编辑器冒烟：画布按 definition 渲染，调色板与 JSON 视图就位', async ({ page }) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (error) => pageErrors.push(error));

  await bootstrap(page, 'dark');
  await page.goto('/workflow/editor/2');
  await waitStable(page);

  await expect(page.getByTestId('workflow-canvas')).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(6);
  await expect(page.locator('.react-flow__edge')).toHaveCount(6);
  await expect(page.getByTestId('palette-script-exec')).toBeVisible();
  await expect(page.getByTestId('palette-condition')).toBeVisible();
  await expect(page.getByTestId('save-draft')).toBeVisible();
  await expect(page.getByTestId('open-publish')).toBeVisible();
  await expect(page.getByTestId('open-trigger')).toBeVisible();

  // condition 节点是 1 入 2 出（选项 3 不退化）：节点上有两个 source handle
  await expect(page.locator('.react-flow__node[data-id="n2"] [data-handleid="true"]')).toBeVisible();
  await expect(page.locator('.react-flow__node[data-id="n2"] [data-handleid="false"]')).toBeVisible();

  // 定义 JSON 与画布同源（展开折叠面板）
  await page.getByText('定义 JSON（只读）').first().click();
  await expect(page.getByTestId('definition-json')).toContainText('"nodes"');
  await expect(page.getByTestId('definition-json')).toContainText('n1');

  expect(pageErrors).toEqual([]);

  await expect(page).toHaveScreenshot('workflow-editor.png', { mask: maskVolatile(page) });
});
