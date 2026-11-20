import { defineConfig, devices } from '@playwright/test';

/**
 * preview 端口：默认 4717，CI 与本机同一个值。
 *
 * 为什么不是 Vite 默认的 4173：那是预览端口里最常用的一个，本机就常年被别的
 * 服务占着。一旦撞上，`reuseExistingServer` 会把那个陌生服务器当成我们的
 * preview，每个用例都以"元素找不到"失败，报错完全指不到真正原因。现在默认端口
 * 换成一个不常见的值，并且 `reuseExistingServer: false`——真撞上了也是明确的
 * EADDRINUSE，而不是一堆莫名其妙的元素找不到。仍可用 PLAYWRIGHT_PREVIEW_PORT
 * 显式覆盖。（Playwright 1.49 的配置不支持异步函数，所以没法在配置里自动挑空闲
 * 端口；那条路要到高版本才有。）
 */
const previewPort = Number(process.env.PLAYWRIGHT_PREVIEW_PORT ?? 4717);
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
    // 不用 `npm run build && npm run preview`：CI 上那条链第二次构建会把 vite 的
    // reporter 输出吞掉一半，npm 的 script 包装层又让 preview 的 banner 完全不出现，
    // 一旦探活失败，日志里看不到任何线索（见 frontend-visual 的首次失败）。
    // 现在 build 只由 workflow 的 step 做一次，这里直接起 vite preview，输出完整、
    // 失败信息明确。strictPort 让端口冲突立刻报错而不是静默换端口。
    command: `npx vite preview --port ${previewPort} --strictPort --host 127.0.0.1`,
    url: baseURL,
    // 不复用：端口被别的服务占用时必须让我们自己的 preview 去撞 EADDRINUSE，
    // 而不是静默连上别人的服务器然后报"元素找不到"（见文件头注释）。
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
