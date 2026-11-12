import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Locator, Page, WebSocketRoute } from '@playwright/test';
import { fallbackFor } from './fixtures';

const fixturesRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'fixtures', 'api');

export interface BootstrapHooks {
  /** routeWebSocket 每次接管一条 WS 连接时回调（ws-intercept.spec.ts 用来计数） */
  onWebSocket?: (ws: WebSocketRoute) => void;
}

/** token + 主题 + locale 的会话 stub（站点把 token 放在 sessionStorage，见 src/api/request.ts:10-18） */
async function bootstrapSession(page: Page, theme: 'dark' | 'light'): Promise<void> {
  await page.addInitScript((t) => {
    sessionStorage.setItem('token', 'stub-token');
    localStorage.setItem('locale', 'zh-CN');
    localStorage.setItem('easyshell-theme-mode', t);
  }, theme);
  // 防误报：钉死 prefers-reduced-motion，避免运行环境（操作系统"减少动态效果"）泄漏进截图。
  // config 的 use 里没有这个键（1.49 的 UseOptions 不含 reducedMotion），只能在页面级 emulate。
  await page.emulateMedia({ reducedMotion: 'no-preference' });
}

/**
 * stub 全部 /api/**：优先 tests/fixtures/api/ 下的同名 fixture，
 * 缺失时按端点真实形状兜底（★ C-4：不用一把梭的 {records,total}）。
 */
async function stubApi(page: Page): Promise<void> {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/host-permissions/me/query' && route.request().method() === 'POST') {
      const { agentIds } = route.request().postDataJSON() as { agentIds: string[] };
      await route.fulfill({ json: {
        code: 200,
        message: 'ok',
        data: agentIds.map((agentId) => ({ agentId, generation: 1, view: true, execute: true, terminal: true })),
      } });
      return;
    }
    const file = `${path.join(fixturesRoot, url.pathname.replace('/api/v1', ''))}.json`;
    try {
      const body = JSON.parse(await readFile(file, 'utf8'));
      await route.fulfill({ json: body });
    } catch {
      await route.fulfill({ json: fallbackFor(url.pathname) });
    }
  });
}

/**
 * 只 stub HTTP API，不碰 WebSocket。ws-intercept.spec.ts 用它复现"没有 routeWebSocket 时
 * WebSocket 直连预览服务器"的对照组。
 */
export async function bootstrapApiOnly(page: Page, theme: 'dark' | 'light' = 'dark'): Promise<void> {
  await bootstrapSession(page, theme);
  await stubApi(page);
}

/**
 * 完整 stub：会话 + HTTP API + WebSocket。
 * 主题键说明：ThemeContext.tsx 用 `easyshell-theme-mode`（值 'dark'/'light'，设计文档 §8.2-W1
 * 起替换旧键 `easyshell-theme`；旧键由 ThemeProvider 一次性迁移清除，不再读取）。
 */
export async function bootstrap(page: Page, theme: 'dark' | 'light', hooks: BootstrapHooks = {}): Promise<void> {
  await bootstrapSession(page, theme);

  // ★ C-3 修复：page.route('**/ws/**') 拦不住 WebSocket（复核官实测：WS OPENED、handler 0 命中）。
  // 模式匹配的坑（实测）：'/ws/**' 不命中（glob 匹配完整 URL ws://host:port/ws/...），必须写 '**/ws/**'。
  await page.routeWebSocket('**/ws/**', (ws) => {
    hooks.onWebSocket?.(ws);
    ws.close(); // 确定性地关闭，正是"未连接"稳态
  });

  await stubApi(page);
}

/** 等渲染稳态：字体就绪 → 网络空闲 → AntD CSS-in-JS 异步注入收尾（防误报②⑤） */
export async function waitStable(page: Page): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(120);
}

/**
 * 防误报③：掩码时序不稳定的动态内容。当前集合 = 全局 toast/通知（请求失败时异步弹出，
 * 出现时机与截图时序竞争，掩掉不影响任何稳态断言）。
 * 时间类内容已由"固定 fixture 时间戳 + timezoneId:'Asia/Shanghai'"钉死，无需掩码；
 * 将来哪张图出现时钟/随机数，往这个数组里加对应 locator。
 */
export function maskVolatile(page: Page): Locator[] {
  return [page.locator('.ant-message'), page.locator('.ant-notification')];
}

export interface WsProbeEntry {
  url: string;
  /** 生命周期事件：`new` 之外的 `open` / `close:<code>` / `error` */
  events: string[];
}

/**
 * 在页面脚本之前 patch WebSocket，把每个实例的生命周期记到 window.__wsProbe。
 *
 * 注意（实测）：Playwright 自己也会在注册了 routeWebSocket 时替换 window.WebSocket，
 * 所以这个探针**只能在没有 routeWebSocket 的对照组里用**（bootstrapApiOnly）——
 * ws-intercept.spec.ts 的"page.route 拦不住 WS"用例正是这样用的。
 */
export async function installWebSocketProbe(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const win = window as unknown as { WebSocket: typeof WebSocket; __wsProbe: WsProbeEntry[] };
    win.__wsProbe = [];
    const NativeWebSocket = win.WebSocket;

    class ProbeWebSocket extends NativeWebSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols);
        const probe: WsProbeEntry = { url: String(url), events: [] };
        win.__wsProbe.push(probe);
        this.addEventListener('open', () => probe.events.push('open'));
        this.addEventListener('close', (event) => probe.events.push(`close:${(event as CloseEvent).code}`));
        this.addEventListener('error', () => probe.events.push('error'));
      }
    }

    Object.defineProperty(win, 'WebSocket', { value: ProbeWebSocket, writable: true, configurable: true });
  });
}

/** 读回 installWebSocketProbe 记录到的 WS 生命周期 */
export async function readWsProbe(page: Page): Promise<WsProbeEntry[]> {
  return page.evaluate(() => (window as unknown as { __wsProbe: WsProbeEntry[] }).__wsProbe);
}

/** xterm 把连接过程写进了 DOM（.xterm-rows），是页面侧观察 WS 生命周期最直接的证据 */
export async function readTerminalText(page: Page): Promise<string> {
  return page.locator('.xterm-rows').innerText();
}
