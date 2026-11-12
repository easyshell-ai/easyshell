import { describe, expect, it } from 'vitest';
import { definitionToFlow, flowToDefinition, isValidNodeKey } from '../../graph';
import { WORKFLOW_TEMPLATES } from './index';

/**
 * 模板构建器的契约闸门：结构上必须能通过 DagValidator 的核心拓扑规则，
 * 且覆盖模板库承诺的节点组合（start/end/variables/transform/condition）。
 */

describe('workflow template builders', () => {
  it('registers exactly the four advertised templates', () => {
    expect(WORKFLOW_TEMPLATES.map((template) => template.id)).toEqual(['disk', 'restart', 'health', 'aiReview']);
  });

  for (const template of WORKFLOW_TEMPLATES) {
    describe(template.id, () => {
      const definition = template.build();

      it('has valid unique node keys and declared variables', () => {
        const keys = definition.nodes.map((node) => node.key);
        expect(new Set(keys).size).toBe(keys.length);
        for (const key of keys) expect(isValidNodeKey(key)).toBe(true);
        expect(Object.keys(definition.variables).length).toBeGreaterThan(0);
      });

      it('has exactly one start (no input) and one end (no output)', () => {
        const starts = definition.nodes.filter((node) => node.type === 'start');
        const ends = definition.nodes.filter((node) => node.type === 'end');
        expect(starts).toHaveLength(1);
        expect(ends).toHaveLength(1);
        expect(definition.edges.some((edge) => edge.to === starts[0].key)).toBe(false);
        expect(definition.edges.some((edge) => edge.from === ends[0].key)).toBe(false);
      });

      it('every edge endpoint exists, ids are unique, condition branches use true/false', () => {
        const keys = new Set(definition.nodes.map((node) => node.key));
        const ids = new Set<string>();
        for (const edge of definition.edges) {
          expect(keys.has(edge.from)).toBe(true);
          expect(keys.has(edge.to)).toBe(true);
          expect(ids.has(edge.id)).toBe(false);
          ids.add(edge.id);
        }
        const conditionKeys = new Set(
          definition.nodes.filter((node) => node.type === 'condition').map((node) => node.key)
        );
        for (const edge of definition.edges.filter((item) => conditionKeys.has(item.from))) {
          expect(['true', 'false']).toContain(edge.sourceHandle);
        }
      });

      it('uses transform mappings and end outputs', () => {
        const transform = definition.nodes.filter((node) => node.type === 'transform');
        expect(transform.length).toBeGreaterThan(0);
        for (const node of transform) {
          expect(Array.isArray(node.config.mappings)).toBe(true);
          expect((node.config.mappings as unknown[]).length).toBeGreaterThan(0);
        }
        const end = definition.nodes.find((node) => node.type === 'end')!;
        expect(Array.isArray(end.config.outputs)).toBe(true);
        expect((end.config.outputs as unknown[]).length).toBeGreaterThan(0);
      });

      it('round-trips through the React Flow adapter without losing structure', () => {
        const flow = definitionToFlow(definition);
        expect(flow.nodes).toHaveLength(definition.nodes.length);
        expect(flow.edges).toHaveLength(definition.edges.length);
        const roundTrip = flowToDefinition(flow.nodes, flow.edges, definition);
        expect(roundTrip.nodes.map((node) => `${node.key}:${node.type}`).sort()).toEqual(
          definition.nodes.map((node) => `${node.key}:${node.type}`).sort()
        );
        expect(roundTrip.edges).toHaveLength(definition.edges.length);
      });
    });
  }

  it('disk template really branches on a condition', () => {
    const disk = WORKFLOW_TEMPLATES.find((template) => template.id === 'disk')!.build();
    const condition = disk.nodes.find((node) => node.type === 'condition')!;
    expect(condition.config.expression).toContain('nodes.metrics.output.usage');
    const outs = disk.edges.filter((edge) => edge.from === condition.key);
    expect(outs.map((edge) => edge.sourceHandle).sort()).toEqual(['false', 'true']);
  });

  it('restart template gates the script behind an approval', () => {
    const restart = WORKFLOW_TEMPLATES.find((template) => template.id === 'restart')!.build();
    const approval = restart.nodes.find((node) => node.type === 'approval')!;
    const script = restart.nodes.find((node) => node.type === 'script-exec')!;
    expect(restart.edges.some((edge) => edge.from === approval.key && edge.to === script.key)).toBe(true);
  });

  it.each(['disk', 'health'])('%s is an offline simulation without external side effects', (id) => {
    const definition = WORKFLOW_TEMPLATES.find((template) => template.id === id)!.build();
    expect(definition.nodes.every((node) => ['start', 'transform', 'condition', 'end'].includes(node.type))).toBe(true);
    const condition = definition.nodes.find((node) => node.type === 'condition')!;
    expect(definition.edges.filter((edge) => edge.from === condition.key).map((edge) => edge.sourceHandle).sort())
      .toEqual(['false', 'true']);
    expect(new Set(definition.edges.map((edge) => `${edge.from}->${edge.to}`)).size).toBe(definition.edges.length);
  });

  it('restart notification does not claim health verification', () => {
    const restart = WORKFLOW_TEMPLATES.find((template) => template.id === 'restart')!.build();
    const message = restart.nodes.find((node) => node.type === 'notify')!.config.messageTemplate;
    expect(message).toContain('health was not verified');
  });
});
