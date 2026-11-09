/**
 * 对齐辅助线与吸附计算（选项 3）。
 *
 * 纯函数：拖动中的节点盒子 vs 其它节点盒子，在阈值内找 x/y 各自最近的一条对齐线，
 * 返回要画的辅助线 + 吸附偏移。组件只在 onNodeDrag 里调它，不碰 React Flow store。
 */

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface AlignmentGuide {
  /** vertical = 一条竖线（对齐左右边/中线），horizontal = 一条横线 */
  orientation: 'vertical' | 'horizontal';
  /** 线在流坐标里的位置 */
  position: number;
  /** 线的起止范围（流坐标，垂直于线的方向） */
  start: number;
  end: number;
}

export interface AlignmentResult {
  guides: AlignmentGuide[];
  /** 吸附后节点左上角应处的位置 */
  snapped: { x: number; y: number };
  snappedX: boolean;
  snappedY: boolean;
}

export const ALIGN_THRESHOLD = 6;

export function computeAlignment(moving: Box, others: Box[], threshold = ALIGN_THRESHOLD): AlignmentResult {
  const movingX: [number, number, number] = [moving.x, moving.x + moving.width / 2, moving.x + moving.width];
  const movingY: [number, number, number] = [moving.y, moving.y + moving.height / 2, moving.y + moving.height];

  let bestX: { delta: number; line: number } | null = null;
  let bestY: { delta: number; line: number } | null = null;

  for (const other of others) {
    const otherX: [number, number, number] = [other.x, other.x + other.width / 2, other.x + other.width];
    const otherY: [number, number, number] = [other.y, other.y + other.height / 2, other.y + other.height];
    const pairs: Array<[number, number, 'x' | 'y']> = [
      [otherX[0], movingX[0], 'x'],
      [otherX[1], movingX[1], 'x'],
      [otherX[2], movingX[2], 'x'],
      [otherY[0], movingY[0], 'y'],
      [otherY[1], movingY[1], 'y'],
      [otherY[2], movingY[2], 'y'],
    ];
    for (const [otherValue, movingValue, axis] of pairs) {
      const delta = otherValue - movingValue;
      if (Math.abs(delta) > threshold) continue;
      if (axis === 'x') {
        if (!bestX || Math.abs(delta) < Math.abs(bestX.delta)) bestX = { delta, line: otherValue };
      } else if (!bestY || Math.abs(delta) < Math.abs(bestY.delta)) {
        bestY = { delta, line: otherValue };
      }
    }
  }

  const guides: AlignmentGuide[] = [];
  if (bestX) {
    guides.push({
      orientation: 'vertical',
      position: moving.x + bestX.delta,
      start: Math.min(moving.y, ...others.map((o) => o.y)),
      end: Math.max(moving.y + moving.height, ...others.map((o) => o.y + o.height)),
    });
  }
  if (bestY) {
    guides.push({
      orientation: 'horizontal',
      position: moving.y + bestY.delta,
      start: Math.min(moving.x, ...others.map((o) => o.x)),
      end: Math.max(moving.x + moving.width, ...others.map((o) => o.x + o.width)),
    });
  }

  return {
    guides,
    snapped: { x: moving.x + (bestX?.delta ?? 0), y: moving.y + (bestY?.delta ?? 0) },
    snappedX: bestX !== null,
    snappedY: bestY !== null,
  };
}
