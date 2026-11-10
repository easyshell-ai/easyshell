import { expect, test } from '@playwright/test';
import { bootstrap, waitStable } from './helpers';

/**
 * 画布性能回归门槛（设计 NF-3，选项 3 归 M1）：
 * 100 节点 + 150 边，拖拽单节点全程的帧时间，不得显著高于同一台机器上的空闲帧时间。
 *
 * 手段与实现一一对应：onlyRenderVisibleElements、React.memo 节点、拖拽不序列化
 * （只 onNodeDragStop 落一次位置）、边不开 animated、snapToGrid 20px、配置面板是 Drawer。
 * 这里用 Playwright 录真实帧时间作回归门槛。
 *
 * 场景说明：图是全量加载的（100 节点 / 150 边都在 store 里），视口先放大到 100%——
 * 这正是 onlyRenderVisibleElements 的用途（只渲染可见元素），也是使用者真实的工作视角。
 *
 * ── 为什么门槛是"比率"而不是绝对值（这个阈值改过三轮，前两轮都在 CI 上误报）────────
 * 1) 第一轮 `P95 < 18ms`：CI runner 是共享核，空闲帧 P95 实测已经 22.6–26.9ms，
 *    绝对值完全被环境噪声主导 → 误报。
 * 2) 第二轮 `median < 18ms` + `P95 < max(空闲 P95 × 2, 40ms)`：CI 上 median 也过不去。
 *    HEAD 3c8db5e 那次 CI 实测（3 个 project 全红，其余 20 条全过）：
 *      · 空闲帧 P95：CI 22.6 / 25.4 / 25.7 / 26.1 / 26.4 / 26.9ms，
 *        开发机 20.2–21.6ms → CI 尾延迟约是开发机的 1.25 倍；
 *      · 拖拽 median：CI 16.4 / 17.4 / 17.7 / 17.9 / 18.7 / 19.5ms（均值 17.9ms），
 *        开发机 16.3–17.4ms（均值 16.8ms）→ CI 只慢约 7%，就足以越过 18ms 这条线；
 *      · 拖拽 P95：CI 53.5–70.5ms，开发机 20.4–28.6ms → CI 尾延迟是开发机的 2–3 倍，
 *        所以任何基于 P95 的倍数（×2）在 CI 上都贴着噪声边缘，必然反复误报。
 *    结论：CI 与开发机的差距集中在"尾延迟"，median 只差 7%。拿绝对值划线，
 *    等于按某一台机器的速度给全局定标准，换台机器就翻车。
 * 3) 第三轮（现在）：改成量纲无关的比率——拖拽 median / 空闲 median。
 *    同一个盒子里空闲和拖拽一起变慢，比率不动，所以与机器快慢无关；
 *    而真实回归（把画布改成每次拖拽都全量重渲染 → 帧时间翻倍）会让比率跳到 2 以上，
 *    在 CI 和开发机上都会被抓住。P95 只作诊断输出，不再当门槛。
 */

/** 拖拽 median 不得超过空闲 median 的这个倍数，即"拖拽比空闲慢不超过 60%"。推导见文末断言处。 */
const DRAG_IDLE_RATIO_BUDGET = 1.6;

/**
 * 拖拽 median 的绝对护栏（ms）= 2 × 16.7ms（60Hz 的一个 vsync 周期）。
 * 比率抓不到"全局一起变慢"这类回归（空闲基线与拖拽同步变慢时比率仍是 1），
 * 这条护栏兜底：median 超过两个 vsync 周期意味着拖拽中有一半帧在掉帧。
 * 它不是按某台机器标定的速度线，而是按 vsync 量子定的；当前最慢的 CI 实测
 * median 是 19.5ms，距 33.4ms 还有 71% 余量。
 */
const DRAG_MEDIAN_CEILING_MS = 33.4;

const DRAG_STEPS = 40;

interface FrameSample {
  t: number;
  dt: number;
}

test('画布性能：100 节点 / 150 边拖拽帧时间不高于空闲基线的 1.6 倍', async ({ page }) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (error) => pageErrors.push(error));

  await bootstrap(page, 'dark');
  await page.goto('/workflow/editor/3');
  await waitStable(page);

  const nodes = page.locator('.react-flow__node');
  await expect.poll(async () => nodes.count(), { timeout: 10_000 }).toBeGreaterThanOrEqual(20);
  console.log(`图已加载：可见节点 ${await nodes.count()} / 100，边 ${await page.locator('.react-flow__edge').count()} / 150`);

  // 帧采样器
  await page.evaluate(() => {
    const win = window as unknown as { __frames: FrameSample[] };
    win.__frames = [];
    let last = performance.now();
    const tick = () => {
      const now = performance.now();
      win.__frames.push({ t: now, dt: now - last });
      last = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

  const takeFrames = () =>
    page.evaluate(() => {
      const win = window as unknown as { __frames: FrameSample[] };
      const copy = win.__frames.slice();
      win.__frames = [];
      return copy;
    });

  const p95 = (frames: FrameSample[]) => {
    const sorted = frames.map((frame) => frame.dt).sort((a, b) => a - b);
    return sorted[Math.max(0, Math.floor(sorted.length * 0.95) - 1)] ?? sorted[sorted.length - 1];
  };

  const median = (frames: FrameSample[]) => {
    const sorted = frames.map((frame) => frame.dt).sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length * 0.5)] ?? sorted[sorted.length - 1];
  };

  // 空闲基线（不拖拽）：区分"环境本身有多快"与"拖拽新增的开销"，
  // 同时它就是比率门槛的分母——同一台机器上测，机器快慢自动抵消。
  await page.waitForTimeout(600);
  const idle = await takeFrames();
  // 基线采样不足时比率没有意义（分母可能是 NaN），直接判失败而不是放过去
  expect(idle.length).toBeGreaterThan(10);
  const idleMedian = median(idle);
  const idleP95 = p95(idle);
  console.log(`空闲帧时间：frames=${idle.length} median=${idleMedian.toFixed(2)}ms p95=${idleP95.toFixed(2)}ms`);

  const target = nodes.first();
  const box = await target.boundingBox();
  expect(box).not.toBeNull();
  const startX = box!.x + box!.width / 2;
  const startY = box!.y + box!.height / 2;

  const startedAt = await page.evaluate(() => performance.now());
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  for (let step = 1; step <= DRAG_STEPS; step += 1) {
    await page.mouse.move(startX + step * 6, startY + step * 2);
  }
  await page.mouse.up();
  const finishedAt = await page.evaluate(() => performance.now());

  const frames = (await takeFrames()).filter((frame) => frame.t >= startedAt && frame.t <= finishedAt);
  expect(frames.length).toBeGreaterThan(20);
  const dragMedian = median(frames);
  const measured = p95(frames);
  const ratio = dragMedian / idleMedian;

  console.log(
    `画布拖拽帧时间：frames=${frames.length} median=${dragMedian.toFixed(2)}ms p95=${measured.toFixed(2)}ms`
  );
  console.log(
    `性能门槛：拖拽/空闲 median 比率=${ratio.toFixed(2)}（预算 < ${DRAG_IDLE_RATIO_BUDGET}），` +
      `拖拽 median < ${DRAG_MEDIAN_CEILING_MS}ms（p95 仅诊断，不作门槛）`
  );

  expect(pageErrors).toEqual([]);
  // 绝对护栏：median 超过两个 vsync 周期 = 拖拽中一半的帧在掉帧（见常量注释）。
  expect(dragMedian).toBeLessThan(DRAG_MEDIAN_CEILING_MS);
  // 主门槛：拖拽相对空闲的劣化幅度。倍数取 1.6 的推导——
  //   · 通过侧：开发机（3 核）4 次实测空闲 median 15.9–17.1ms、拖拽 median 16.9–18.0ms，
  //     比率 0.99–1.07；CI 只打印过空闲 P95（22.6–26.9ms），median 未打印过（本轮才加），
  //     按开发机量级估，最脏的那个 CI 样本（拖拽 19.5ms）比率约 1.2。健康区间 ≈ 1.0–1.2。
  //   · 拒绝侧：帧时间翻倍的全量重渲染回归，比率会到 2.0–2.4。
  //   · 1.6 取两侧的几何中点 √(1.2 × 2.2) ≈ 1.6：离噪声 33%、离回归 25%，两边都留足余量。
  // 若哪天这条又红了，先看日志里的比率：> 1.6 且拖拽 median 同步上涨 = 真回归；
  // 比率没动而拖拽/空闲绝对值一起涨 = 机器变慢，该调的是 DRAG_MEDIAN_CEILING_MS。
  expect(ratio).toBeLessThan(DRAG_IDLE_RATIO_BUDGET);
});
