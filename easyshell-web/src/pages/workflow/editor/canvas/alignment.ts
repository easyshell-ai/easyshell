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

  let bestX: number | null = null;
  let bestY: number | null = null;
  let minX = moving.x;
  let maxX = moving.x + moving.width;
  let minY = moving.y;
  let maxY = moving.y + moving.height;

  for (const other of others) {
    minX = Math.min(minX, other.x);
    maxX = Math.max(maxX, other.x + other.width);
    minY = Math.min(minY, other.y);
    maxY = Math.max(maxY, other.y + other.height);
    for (let i = 0; i < 3; i++) {
      const otherX = i === 0 ? other.x : i === 1 ? other.x + other.width / 2 : other.x + other.width;
      const otherY = i === 0 ? other.y : i === 1 ? other.y + other.height / 2 : other.y + other.height;
      const deltaX = otherX - movingX[i];
      const deltaY = otherY - movingY[i];
      if (!(Math.abs(deltaX) > threshold) && (bestX === null || Math.abs(deltaX) < Math.abs(bestX))) bestX = deltaX;
      if (!(Math.abs(deltaY) > threshold) && (bestY === null || Math.abs(deltaY) < Math.abs(bestY))) bestY = deltaY;
    }
  }

  const guides: AlignmentGuide[] = [];
  if (bestX !== null) {
    guides.push({
      orientation: 'vertical',
      position: moving.x + bestX,
      start: minY,
      end: maxY,
    });
  }
  if (bestY !== null) {
    guides.push({
      orientation: 'horizontal',
      position: moving.y + bestY,
      start: minX,
      end: maxX,
    });
  }

  return {
    guides,
    snapped: { x: moving.x + (bestX ?? 0), y: moving.y + (bestY ?? 0) },
    snappedX: bestX !== null,
    snappedY: bestY !== null,
  };
}
