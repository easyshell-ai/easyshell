import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import WorkflowCanvas, { reuseUnchangedGuides, type WorkflowCanvasProps } from './WorkflowCanvas';
import { semanticVar } from '../../../../theme/semantics';
import type { AlignmentGuide } from './alignment';

const miniMap = vi.hoisted(() => ({ props: undefined as undefined | {
  nodeColor: (node: { id: string }) => string;
  pannable: boolean;
  zoomable: boolean;
  nodeStrokeWidth: number;
} }));

vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>();
  return {
    ...actual,
    ReactFlowProvider: ({ children }: { children: React.ReactNode }) => children,
    ReactFlow: ({ children }: { children: React.ReactNode }) => children,
    MiniMap: (props: typeof miniMap.props) => { miniMap.props = props; return null; },
    Background: () => null,
    Controls: () => null,
    Panel: () => null,
    ViewportPortal: () => null,
    useReactFlow: () => ({ screenToFlowPosition: vi.fn() }),
  };
});

vi.mock('../../../../contexts/themeContext', () => ({ useTheme: () => ({ isDark: false }) }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

afterEach(cleanup);

describe('WorkflowCanvas MiniMap nodeColor', () => {
  it('keeps its identity across unrelated renders and reflects changed statuses', () => {
    const statuses = { a: 'running', b: 'unexpected', c: '' };
    const props = {
      nodes: [], edges: [], statuses,
      connectionContext: {},
    } as unknown as WorkflowCanvasProps;
    const view = render(<WorkflowCanvas {...props} />);
    const initial = miniMap.props!;
    expect(initial).toMatchObject({ pannable: true, zoomable: true, nodeStrokeWidth: 3 });
    expect(initial.nodeColor({ id: 'a' })).toBe(semanticVar('running'));
    expect(initial.nodeColor({ id: 'b' })).toBe(semanticVar('pending'));
    expect(initial.nodeColor({ id: 'c' })).toBe('var(--es-color-border)');
    expect(initial.nodeColor({ id: 'missing' })).toBe('var(--es-color-border)');

    view.rerender(<WorkflowCanvas {...props} readOnly />);
    expect(miniMap.props!.nodeColor).toBe(initial.nodeColor);

    view.rerender(<WorkflowCanvas {...props} statuses={{ a: 'succeeded' }} />);
    expect(miniMap.props!.nodeColor).not.toBe(initial.nodeColor);
    expect(miniMap.props!.nodeColor({ id: 'a' })).toBe(semanticVar('succeeded'));
    expect(miniMap.props!.nodeColor({ id: 'b' })).toBe('var(--es-color-border)');

    view.rerender(<WorkflowCanvas {...props} statuses={undefined} />);
    expect(miniMap.props!.nodeColor({ id: 'a' })).toBe('var(--es-color-border)');
  });
});

describe('reuseUnchangedGuides', () => {
  const guide: AlignmentGuide = { orientation: 'vertical', position: 10, start: 20, end: 30 };

  it('reuses the previous array for identical guides, including empty arrays', () => {
    const empty: AlignmentGuide[] = [];
    expect(reuseUnchangedGuides(empty, [])).toBe(empty);
    const previous = [guide, { ...guide, orientation: 'horizontal' as const }];
    expect(reuseUnchangedGuides(previous, previous.map((item) => ({ ...item })))).toBe(previous);
  });

  it.each([
    { orientation: 'horizontal' as const },
    { position: 11 },
    { start: 21 },
    { end: 31 },
  ])('uses the new array when a guide changes: %o', (change) => {
    const next = [{ ...guide, ...change }];
    expect(reuseUnchangedGuides([guide], next)).toBe(next);
  });

  it('uses the new array when guides are added, removed, or reordered', () => {
    const other: AlignmentGuide = { ...guide, orientation: 'horizontal' };
    const previous = [guide, other];
    for (const next of [[], [guide], [other, guide], [guide, other, guide]]) {
      expect(reuseUnchangedGuides(previous, next)).toBe(next);
    }
  });
});
