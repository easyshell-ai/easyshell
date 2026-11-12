import { act, cleanup, render, screen } from '@testing-library/react';
import { Profiler } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReactFlowProvider, useStoreApi, Position, type EdgeProps } from '@xyflow/react';
import type { WorkflowFlowEdge } from '../../graph';
import WorkflowEdge from './WorkflowEdge';

vi.mock('@xyflow/react', async (importOriginal) => {
  const original = await importOriginal<typeof import('@xyflow/react')>();
  return { ...original, EdgeLabelRenderer: ({ children }: { children: React.ReactNode }) => <>{children}</> };
});

afterEach(cleanup);

describe('WorkflowEdge source subscriptions', () => {
  it('tracks each source type without rerendering on unrelated node changes', () => {
    let store: ReturnType<typeof useStoreApi>;
    let commits = 0;
    function CaptureStore() {
      store = useStoreApi();
      return null;
    }
    const edge = (id: string, source: string, sourceHandleId: string, sourceType?: 'condition') => ({
      id, source, target: 'target', sourceHandleId, sourceX: 0, sourceY: 0,
      targetX: 100, targetY: 100, sourcePosition: Position.Right, targetPosition: Position.Left,
      data: { sourceType },
    }) as EdgeProps<WorkflowFlowEdge>;
    render(
      <ReactFlowProvider>
        <CaptureStore />
        <svg><Profiler id="edges" onRender={() => { commits += 1; }}>
          <WorkflowEdge {...edge('a', 'one', 'true', 'condition')} />
          <WorkflowEdge {...edge('b', 'two', 'urgent')} />
        </Profiler></svg>
      </ReactFlowProvider>,
    );
    expect(screen.getByText('IF')).toBeTruthy(); // fallback when source is missing
    const updateNodes = (nodes: Record<string, { nodeType: string; position?: number }>) => {
      const nodeLookup = new Map(Object.entries(nodes).map(([id, data]) => [id, { id, data }]));
      // The selector only reads data.nodeType; these are intentionally partial internal-node test doubles.
      act(() => store.setState({ nodeLookup: nodeLookup as unknown as ReturnType<typeof store.getState>['nodeLookup'] }));
    };
    updateNodes({ one: { nodeType: 'condition' }, two: { nodeType: 'classifier' } });
    expect(screen.getByText('IF')).toBeTruthy();
    expect(screen.getByText('urgent')).toBeTruthy();
    const previousCommits = commits;
    updateNodes({ one: { nodeType: 'condition', position: 1 }, two: { nodeType: 'classifier', position: 2 } });
    expect(commits).toBe(previousCommits);
    updateNodes({ one: { nodeType: 'classifier' }, two: { nodeType: 'condition' } });
    expect(screen.queryByText('IF')).toBeNull();
    expect(screen.getByText('true')).toBeTruthy();
    expect(screen.queryByText('urgent')).toBeNull();
    updateNodes({});
    expect(screen.getByText('IF')).toBeTruthy();
    expect(screen.queryByText('true')).toBeNull();
  });
});
