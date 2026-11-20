# tests/visual — 视觉回归（Playwright）

前端重构（1.3.0）的安全网：每次样式改动都由机器 diff 兜底，不靠人眼截图对比。
设计文档：`.omo/reports/frontend-redesign-design-v3.md` §6。

## 跑起来

```bash
cd easyshell-web
npx playwright install --with-deps chromium   # 首次（或 CI 里）
npm run test:visual                            # = npx playwright test
```

- `playwright.config.ts` 的 `webServer` 会先 `npm run build && npm run preview`，测的是**生产构建**，
  不依赖真实后端（API 全走 stub，见下）。
- 本地已有 preview 在跑时会复用它（`reuseExistingServer`），省一次 build。
- 端口默认 4173；被别的服务占用时 `PLAYWRIGHT_PREVIEW_PORT=4180 npm run test:visual` 换端口
  （否则 `reuseExistingServer` 会误抓到别人的服务器，锚点断言会立刻报错，不会静默测错应用）。
- **首次运行（无基线时）Playwright 会写入基线图但用例判失败**，这是它的既定行为；
  再跑一次即全绿。所以"生成基线"和"验证通过"是两次运行。
- 冒烟基线 = 6 个核心路由 × `dark-1440`：login / host 列表 / task 列表 / terminal / ai/chat / settings。
  `dark-1920`、`light-1440` 两个 project 已配好但 W0 未铺基线，逐路由铺开时删掉
  `routes.spec.ts` 里的 `test.skip(...)` 再更新基线。

## 怎么更新基线

```bash
npm run test:visual:update                 # 全量重生成
npm run test:visual:update -- -g "路由:/host"   # 只更新某一个
```

**纪律**：`--update-snapshots` 必须由人显式执行，且与"改样式"在**同一个 commit/PR**里，
防止顺手把回归基线一起吞掉。CI 上禁止自动更新基线。误报优先修基建（等稳态、掩码、fixture），
不要拿 `--update-snapshots` 掩盖。基线图只提交核心路由的冒烟图，不要一次提交几百张。

## CI 怎么工作

`.github/workflows/frontend-visual.yml`：PR/push 到 main 且动了 `easyshell-web/**` 时触发，
跑 `lint && build && test && playwright test`。基线图 diff 的呈现方式：

- 失败用例由 `github` reporter 在 PR Checks 里标注；
- `playwright-report/`（html 报告，含 expected/actual/diff 三图对比）与 `test-results/`
  （diff 三件套）作 artifact 上传，PR 页底部下载。

## 误报怎么处理（八条防误报规则）

1. CSS 动画/过渡：`expect.toHaveScreenshot.animations:'disabled'`（截图级，真正生效的那份；
   `use.animations` 在 pinned 的 1.49 里不是合法选项，写了会被静默忽略，见 config 注释）。
2. 字体：截图前 `document.fonts.ready`（`helpers.ts:waitStable`）。
3. 网络：`waitForLoadState('networkidle')` + 120ms 等 AntD CSS-in-JS 异步注入收尾。
4. 动态内容：`maskVolatile()` 掩掉全局 toast/通知；时间类内容由固定 fixture 时间戳 +
   `timezoneId:'Asia/Shanghai'` + `locale:'zh-CN'` 钉死（`FileTree` 的 `toLocaleString()` 依赖这两项）。
5. 系统主题/语言/时区：config 的 `use` 里钉死；`prefers-reduced-motion` 在 `bootstrap()` 里用
   `page.emulateMedia({ reducedMotion: 'no-preference' })` 钉死（1.49 的 `use` 选项不含它）。
6. `deviceScaleFactor: 1`。
7. 偶发慢一帧：CI `retries: 1`，连续两次失败才判红。
8. 基线图被压缩工具改写：根 `.gitattributes` 对 `*.png` 标 `binary`。

仍误报时的顺序：查是不是 fixture 引入了新字段/新文案 → 补 `waitStable` 的锚点 →
`maskVolatile` 加 locator → 最后才考虑阈值（`maxDiffPixelRatio` 现为 0.002）。

## stub 与 WebSocket（两条实测修正，别改回去）

- `page.route('**/ws/**')` **拦不住 WebSocket**（实测 WS 直连服务器、handler 0 命中）。
  必须用 `page.routeWebSocket('**/ws/**', ws => ws.close())`，且模式必须写 `'**/ws/**'`
  （`'/ws/**'` 对不上完整 URL `ws://host:port/ws/...`）。`ws-intercept.spec.ts` 把这条结论固化成了
  双方案对照断言。
- API stub 按三类真实响应形状分流（对象 / 数组 / 分页），兜底逻辑在 `fixtures.ts`，
  端点速查表在 `tests/fixtures/README.md`。数组型端点永远给 `data: []`，
  否则 `host/index.tsx` 的 `entries.forEach` 会 TypeError 崩页。
- **坑：spec 文件里不能把 glob 字符串写进 `/* */` 块注释。** Playwright 的 spec 加载器会把注释里
  出现的"星号斜杠"序列当成注释结尾，剩下的文字被当成代码执行，整个文件加载即
  `ReferenceError: xxx is not defined`。glob（如 `'**/ws/**'`）只写在行注释或代码里。

## a11y（W9）

`@axe-core/playwright` 已作为 devDependency 装好（设计文档 §2.3），W9 会在 `tests/a11y/` 铺
23 路由的 axe 扫描（验收 0 critical / 0 serious）。当前 UI 已有若干违规（登录页实测
color-contrast serious ×1、region moderate ×5 等），修完再开扫描，不要提前开红。

## 文件清单

| 文件 | 作用 |
| --- | --- |
| `../playwright.config.ts` | snapshotDir / projects / 防误报阈值 / webServer |
| `../tsconfig.test.json` | 让 `tsc -b`（= `npm run build`）也类型检查 tests 与 config——Playwright 只剥类型不检查类型 |
| `helpers.ts` | bootstrap（token+主题+stub+WS）、waitStable、maskVolatile、WS 探针 |
| `fixtures.ts` | 按端点形状的兜底 stub |
| `routes.spec.ts` | 6 路由冒烟截图（dark-1440） |
| `ws-intercept.spec.ts` | routeWebSocket vs page.route 的固化断言 |
| `../fixtures/api/**` | 各端点 fixture 数据 |
| `../../.visual-baselines/**` | 基线图（入库的唯一 png） |
