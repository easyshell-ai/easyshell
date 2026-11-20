import { describe, expect, it } from 'vitest';
import { HistoryStack } from './HistoryStack';

interface Snapshot {
  nodes: string[];
}

let clock = 1000;
const now = () => clock;

describe('HistoryStack', () => {
  it('undo/redo walks the command stack in order', () => {
    const stack = new HistoryStack<Snapshot>({ now });
    const a: Snapshot = { nodes: ['a'] };
    const b: Snapshot = { nodes: ['a', 'b'] };
    const c: Snapshot = { nodes: ['a', 'b', 'c'] };

    stack.push('add-node', a, b);
    clock += 1000;
    stack.push('add-node', b, c);

    expect(stack.canUndo).toBe(true);
    expect(stack.canRedo).toBe(false);
    expect(stack.undo()).toEqual(b);
    expect(stack.undo()).toEqual(a);
    expect(stack.canUndo).toBe(false);
    expect(stack.undo()).toBeNull();

    expect(stack.redo()).toEqual(b);
    expect(stack.redo()).toEqual(c);
    expect(stack.redo()).toBeNull();
  });

  it('a new command clears the redo branch', () => {
    const stack = new HistoryStack<Snapshot>({ now });
    stack.push('x', { nodes: [] }, { nodes: ['a'] });
    stack.undo();
    expect(stack.canRedo).toBe(true);
    clock += 1000;
    stack.push('y', { nodes: [] }, { nodes: ['z'] });
    expect(stack.canRedo).toBe(false);
  });

  it('coalesces same-label commands inside the window and splits outside it', () => {
    const stack = new HistoryStack<Snapshot>({ now, coalesceWindowMs: 800 });
    stack.push('config:a:name', { nodes: [] }, { nodes: ['a'] });
    clock += 300;
    stack.push('config:a:name', { nodes: ['a'] }, { nodes: ['ab'] });
    clock += 300;
    stack.push('config:a:name', { nodes: ['ab'] }, { nodes: ['abc'] });

    expect(stack.undoDepth).toBe(1);

    clock += 5000;
    stack.push('config:a:name', { nodes: ['abc'] }, { nodes: ['abcd'] });
    expect(stack.undoDepth).toBe(2);
    expect(stack.undo()).toEqual({ nodes: ['abc'] });
    expect(stack.undo()).toEqual({ nodes: [] });
  });

  it('keeps distinct labels as separate commands', () => {
    const stack = new HistoryStack<Snapshot>({ now });
    stack.push('add-node', { nodes: [] }, { nodes: ['a'] });
    stack.push('move-node', { nodes: ['a'] }, { nodes: ['a2'] });
    expect(stack.undoDepth).toBe(2);
  });

  it('drops the oldest command past the limit', () => {
    const stack = new HistoryStack<Snapshot>({ now, limit: 3 });
    for (let i = 0; i < 5; i += 1) {
      clock += 2000;
      stack.push(`cmd-${i}`, { nodes: [`v${i}`] }, { nodes: [`v${i + 1}`] });
    }
    expect(stack.undoDepth).toBe(3);
    expect(stack.undo()).toEqual({ nodes: ['v4'] });
    expect(stack.undo()).toEqual({ nodes: ['v3'] });
    expect(stack.undo()).toEqual({ nodes: ['v2'] });
    expect(stack.undo()).toBeNull();
  });

  it('clear empties both stacks', () => {
    const stack = new HistoryStack<Snapshot>({ now });
    stack.push('x', { nodes: [] }, { nodes: ['a'] });
    clock += 1000;
    stack.push('y', { nodes: ['a'] }, { nodes: ['a', 'b'] });
    stack.undo();
    stack.clear();
    expect(stack.canUndo).toBe(false);
    expect(stack.canRedo).toBe(false);
  });
});
