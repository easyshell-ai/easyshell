import { defineConfig, devices } from '@playwright/test';

/**
 * preview 端口：默认 4173（CI 用）。本机 4173 被别的服务占用时，
 * 用 PLAYWRIGHT_PREVIEW_PORT=4180 npm run test:visual 换端口，避免
 * reuseExistingServer 误抓到别人的服务器。
 */
const previewPort = Number(process.env.PLAYWRIGHT_PREVIEW_PORT ?? 4173);
const baseURL = `http://127.0.0.1:${previewPort}`;

/**
 * EasyShell 视觉回归基建（设计文档 frontend-redesign-design-v3.md §6，W0 落地）。
 *
 * 三条实测修正（复核官在真实 Chromium 上证伪过 v2 方案）：
 * 1. `snapshotDir` 必须显式配置——默认值落在 `<testDir>/<spec>-snapshots/`，
 *    那里的 png 会被仓库根 `.gitignore` 的 `*.png` 吃掉，基线永远入不了库；
 *    指向 `.visual-baselines/` 后由 `.gitignore` 的 negation 规则豁免（见 §6.3）。
 * 2. WebSocket 只能用 `routeWebSocket` 拦（`page.route` 对 WS 是死代码），
 *    见 tests/visual/helpers.ts 与 ws-intercept.spec.ts 的固化用例。
 * 3. API stub 必须按真实响应形状分流（ApiResponse / 数组 / PageResponse），
 *    见 tests/visual/fixtures.ts 的 fallbackFor。
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI, // CI 上禁止 test.only，防止只跑一部分把回归放过去
  retries: process.env.CI ? 1 : 0, // 防误报⑦：CI 重试一次，连续两次失败才判红
  workers: process.env.CI ? 2 : undefined,
  reporter: [['html', { open: 'never' }], ['github']],
  // ★ C-2 修复：基线图必须落在入库目录（<snapshotDir>/<spec 目录>/<spec 名>-snapshots/<图名>-<project>-<platform>.png）
  snapshotDir: '.visual-baselines',
  use: {
    baseURL, // vite preview
    // 防误报④：系统主题/语言/时区在 config 里钉死，不随运行机器泄漏
    colorScheme: 'dark',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1, // 防误报⑥
    // 防误报①：禁 CSS 动画/过渡。设计文档 §6.2 在 use 里也写了一份 animations:'disabled'，
    // 但 pinned 的 @playwright/test 1.49 的 use 选项里没有这个键（写了会被静默忽略，
    // tsconfig.test.json 会报 TS2769）；截图级设置才是真正生效的那份，见下面 expect 块。
    trace: 'retain-on-failure',
  },
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.002, // 1440×900 ≈ 2600 像素容差
      animations: 'disabled', // 防误报①：禁 CSS 动画/过渡（xterm 光标闪烁也走 CSS animation）
      caret: 'hide',
    },
  },
  projects: [
    { name: 'dark-1440', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'dark-1920', use: { ...devices['Desktop Chrome'], viewport: { width: 1920, height: 1080 } } },
    { name: 'light-1440', use: { ...devices['Desktop Chrome'], colorScheme: 'light', viewport: { width: 1440, height: 900 } } },
  ],
  webServer: {
    command: `npm run build && npm run preview -- --port ${previewPort}`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
