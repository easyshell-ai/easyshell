import { expect, test } from '@playwright/test';
import { bootstrap, maskVolatile, waitStable } from './helpers';

/**
 * W0 冒烟基线：6 个核心路由 × dark-1440（设计文档 §6，验收 = 基线图入库 + 全绿）。
 *
 * 说明：
 * - dark-1920 / light-1440 两个 project 已在 playwright.config.ts 配好，但 W0 不铺基线
 *   （避免一次提交几百张图）；逐路由铺开时删掉下面的 test.skip，再
 *   `npm run test:visual:update -- -g "路由:"` 生成。
 * - 每条用例都断言 0 page error（C-4 的崩页检验）——截图前先证明页面真的渲染成功了。
 */
const ROUTES: Array<{ name: string; path: string }> = [
  { name: 'login', path: '/login' },
  { name: 'host-list', path: '/host' },
  { name: 'task-list', path: '/task' },
  { name: 'terminal', path: '/terminal/demo-agent-001' },
  { name: 'ai-chat', path: '/ai/chat' },
  { name: 'settings', path: '/system/config' },
];

for (const route of ROUTES) {
  test(`路由:${route.name}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'dark-1440', 'W0 只生成 dark-1440 冒烟基线');

    const pageErrors: Error[] = [];
    page.on('pageerror', (error) => pageErrors.push(error));

    await bootstrap(page, 'dark');
    await page.goto(route.path);
    await waitStable(page);

    // 路由级锚点：证明页面渲染到了预期稳态（含 fixture 数据），而不是截到空白/报错页
    switch (route.path) {
      case '/login':
        await expect(page.getByRole('heading', { name: 'EasyShell' })).toBeVisible();
        break;
      case '/host':
        await expect(page.getByText('prod-web-01')).toBeVisible();
        break;
      case '/task':
        await expect(page.getByText('Nginx 访问日志清理')).toBeVisible();
        break;
      case '/terminal/demo-agent-001':
        // WS 被 routeWebSocket 确定性地关闭 → 状态 pill 落到"已断开"
        await expect(page.getByText('已断开').first()).toBeVisible();
        break;
      case '/ai/chat':
        await expect(page.locator('textarea').first()).toBeVisible();
        break;
      case '/system/config':
        await expect(page.getByText('server.external-url')).toBeVisible();
        break;
      default:
        await expect(page.getByRole('table').first()).toBeVisible();
        break;
    }

    expect(pageErrors).toEqual([]);

    await expect(page).toHaveScreenshot(`${route.name}.png`, { mask: maskVolatile(page) });
  });
}
