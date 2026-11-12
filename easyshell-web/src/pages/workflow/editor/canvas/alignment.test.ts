import { describe, expect, it } from 'vitest';
import { ALIGN_THRESHOLD, computeAlignment, type AlignmentGuide, type Box } from './alignment';

const box = (x: number, y: number, width = 220, height = 72) => ({ x, y, width, height });

// Deliberately mirrors the original allocation-based algorithm as a behavior oracle.
function referenceAlignment(moving: Box, others: Box[], threshold = ALIGN_THRESHOLD) {
  const movingX = [moving.x, moving.x + moving.width / 2, moving.x + moving.width];
  const movingY = [moving.y, moving.y + moving.height / 2, moving.y + moving.height];
  let bestX: number | null = null;
  let bestY: number | null = null;
  for (const other of others) {
    const otherX = [other.x, other.x + other.width / 2, other.x + other.width];
    const otherY = [other.y, other.y + other.height / 2, other.y + other.height];
    const pairs: Array<[number, number, 'x' | 'y']> = [
      ...otherX.map((value, i): [number, number, 'x'] => [value, movingX[i], 'x']),
      ...otherY.map((value, i): [number, number, 'y'] => [value, movingY[i], 'y']),
    ];
    for (const [value, movingValue, axis] of pairs) {
      const delta = value - movingValue;
      if (Math.abs(delta) > threshold) continue;
      if (axis === 'x') {
        if (bestX === null || Math.abs(delta) < Math.abs(bestX)) bestX = delta;
      } else if (bestY === null || Math.abs(delta) < Math.abs(bestY)) bestY = delta;
    }
  }
  const guides: AlignmentGuide[] = [];
  if (bestX !== null) guides.push({ orientation: 'vertical', position: moving.x + bestX,
    start: Math.min(moving.y, ...others.map((o) => o.y)), end: Math.max(moving.y + moving.height, ...others.map((o) => o.y + o.height)) });
  if (bestY !== null) guides.push({ orientation: 'horizontal', position: moving.y + bestY,
    start: Math.min(moving.x, ...others.map((o) => o.x)), end: Math.max(moving.x + moving.width, ...others.map((o) => o.x + o.width)) });
  return { guides, snapped: { x: moving.x + (bestX ?? 0), y: moving.y + (bestY ?? 0) }, snappedX: bestX !== null, snappedY: bestY !== null };
}

describe('computeAlignment', () => {
  it('snaps the left edge to another node and reports a vertical guide', () => {
    const result = computeAlignment(box(103, 300), [box(100, 0), box(600, 400)]);
    expect(result.snappedX).toBe(true);
    expect(result.snapped.x).toBe(100);
    expect(result.snapped.y).toBe(300);
    expect(result.guides).toHaveLength(1);
    expect(result.guides[0].orientation).toBe('vertical');
    expect(result.guides[0].position).toBe(100);
    expect(result.guides[0].start).toBe(0);
    expect(result.guides[0].end).toBe(472);
  });

  it('snaps the top edge and reports a horizontal guide', () => {
    const result = computeAlignment(box(400, 198), [box(0, 200)]);
    expect(result.snappedY).toBe(true);
    expect(result.snapped.y).toBe(200);
    expect(result.guides[0].orientation).toBe('horizontal');
  });

  it('snaps center-to-center', () => {
    const result = computeAlignment(box(2, 500), [box(0, 0)]);
    expect(result.snapped.x).toBe(0);
  });

  it('does not snap outside the threshold', () => {
    const result = computeAlignment(box(130, 130), [box(0, 0)]);
    expect(result.snappedX).toBe(false);
    expect(result.snappedY).toBe(false);
    expect(result.guides).toEqual([]);
    expect(result.snapped).toEqual({ x: 130, y: 130 });
  });

  it('picks the closest candidate when several are in range', () => {
    const result = computeAlignment(box(104, 0), [box(100, 0), box(107, 300)]);
    expect(result.snapped.x).toBe(107);
  });

  it('handles an empty canvas', () => {
    const result = computeAlignment(box(10, 10), []);
    expect(result.snapped).toEqual({ x: 10, y: 10 });
    expect(result.guides).toEqual([]);
    expect(result.snappedX).toBe(false);
    expect(result.snappedY).toBe(false);
  });

  it('keeps the first candidate when deltas tie, including within a node', () => {
    expect(computeAlignment(box(10, 10, 10, 10), [box(8, 12, 18, 2)], 2).snapped).toEqual({ x: 8, y: 12 });
    expect(computeAlignment(box(10, 10), [box(8, 8), box(12, 12)], 2).snapped).toEqual({ x: 8, y: 8 });
  });

  it('matches the original algorithm across deterministic random inputs', () => {
    let seed = 0x12345678;
    const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed; };
    const randomBox = () => box((random() % 101) - 50, (random() % 101) - 50, random() % 40, random() % 40);
    for (let i = 0; i < 3000; i++) {
      const moving = randomBox();
      const others = Array.from({ length: random() % 12 }, randomBox);
      const threshold = (random() % 12) - 2;
      expect(computeAlignment(moving, others, threshold)).toEqual(referenceAlignment(moving, others, threshold));
    }
  });
});
