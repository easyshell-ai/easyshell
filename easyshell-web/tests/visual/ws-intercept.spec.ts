import { expect, test } from '@playwright/test';
import {
  bootstrap,
  bootstrapApiOnly,
  installWebSocketProbe,
  readTerminalText,
  readWsProbe,
  waitStable,
} from './helpers';

// 固化复核官的实测结论（设计文档 §6.4，双方案对照）：
//   page.route 方案      → WS 直连服务器、handler 命中 0 次（死代码）
//   routeWebSocket 方案  → WS 被接管并关闭、handler 命中 1 次
//
// 这两条用例就是那两行输出的回归测试：谁把 helpers.ts 的 routeWebSocket 改回 page.route，
// 或者把模式写成 '/ws' 前缀的形式（前导斜杠对不上完整 URL ws://host:port/ws/...），这里立刻红。
//
// 注意（踩过的坑）：Playwright 的 spec 加载器会把块注释里出现的 "星号斜杠" 序列当成注释结尾，
// 所以 glob 字符串（如 '**/ws/**'）只能写在行注释或代码里，不能写进 /* */ 块注释。

test('routeWebSocket 能接管 /ws/** 并确定性地关闭连接', async ({ page }) => {
  const pageRouteHits: string[] = [];
  const wsHandlerHits: string[] = [];

  // 死代码探针：有人用 page.route 拦 WS 时，它永远不会被命中
  await page.route('**/ws/**', async (route) => {
    pageRouteHits.push(route.request().url());
  });

  await bootstrap(page, 'dark', {
    onWebSocket: (ws) => wsHandlerHits.push(ws.url()),
  });

  await page.goto('/terminal/demo-agent-001');
  await waitStable(page);

  // 1. routeWebSocket 确实接管了一条到 /ws/terminal/ 的连接
  expect(wsHandlerHits).toHaveLength(1);
  expect(wsHandlerHits[0]).toContain('/ws/terminal/demo-agent-001');

  // 2. 页面侧观察到的是"关闭"而非"连接成功"（xterm 的连接过程写在 DOM 里）
  const terminalText = await readTerminalText(page);
  expect(terminalText).toContain('连接已断开');
  expect(terminalText).not.toContain('已连接');

  // 3. page.route 对 WebSocket 完全无效
  expect(pageRouteHits).toEqual([]);
});

test('page.route 拦不住 WebSocket：连接直穿到服务器（对照组）', async ({ page }) => {
  const pageRouteHits: string[] = [];
  await page.route('**/ws/**', async (route) => {
    pageRouteHits.push(route.request().url());
  });

  await installWebSocketProbe(page);
  await bootstrapApiOnly(page); // 不注册 routeWebSocket，让 WS 真实发起

  await page.goto('/terminal/demo-agent-001');
  await waitStable(page);

  // 1. 页面真的向 /ws/terminal/ 发了 WS，且从未 OPEN、以 1006 异常关闭——
  //    说明它一路直穿到了预览服务器（那里没有 /ws 端点），page.route 没有接管它
  const probes = await readWsProbe(page);
  expect(probes).toHaveLength(1);
  expect(probes[0].url).toContain('/ws/terminal/demo-agent-001');
  expect(probes[0].events).not.toContain('open');
  expect(probes[0].events).toContain('close:1006');

  // 2. page.route 的 handler 一次都没命中
  expect(pageRouteHits).toEqual([]);

  // 3. 页面侧同样只看到失败，看不到"已连接"
  const terminalText = await readTerminalText(page);
  expect(terminalText).toContain('连接已断开');
  expect(terminalText).not.toContain('已连接');
});
