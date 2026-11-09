import { describe, expect, it } from 'vitest';
import { computeAlignment } from './alignment';

const box = (x: number, y: number, width = 220, height = 72) => ({ x, y, width, height });

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
  });
});
