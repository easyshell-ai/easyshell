import { describe, expect, it } from 'vitest';
import { definitionToFlow, flowToDefinition } from '../../graph';
import { buildAiReviewTemplate } from './aiReview';

describe('manual AI review template', () => {
  const definition = buildAiReviewTemplate();
  const review = definition.nodes.find((node) => node.key === 'review')!;

  it('requires manual input and uses a real configured default model', () => {
    expect(definition.variables.observation).toMatchObject({ type: 'string', required: true });
    expect(definition.variables.observation.default).toBeUndefined();
    expect(review.config).toMatchObject({ provider: null, model: null, outputMode: 'json_schema_v1' });
    expect(review.config.userPrompt).toContain('${run.input.observation}');
    expect(definition.nodes.map((node) => node.type)).toEqual(['start', 'llm', 'condition', 'transform', 'end']);
  });

  it('requires exactly the two typed fields and aborts without retries on failure', () => {
    expect(JSON.parse(review.config.jsonSchema as string)).toEqual({
      type: 'object',
      properties: { needs_attention: { type: 'boolean' }, summary: { type: 'string' } },
      required: ['needs_attention', 'summary'],
      additionalProperties: false,
    });
    expect(review.onFailure).toBe('abort');
    expect(review.retryMax).toBe(0);
  });

  it('routes both condition handles and exposes only review outputs', () => {
    expect(definition.nodes.find((node) => node.key === 'attention')!.config.expression)
      .toBe('nodes.review.output.json.needs_attention == true');
    expect(definition.edges.filter((edge) => edge.from === 'attention').map((edge) => edge.sourceHandle).sort())
      .toEqual(['false', 'true']);
    expect(definition.nodes.find((node) => node.key === 'end')!.config.outputs).toEqual([
      { name: 'needs_attention', value: '${nodes.review.output.json.needs_attention}' },
      { name: 'summary', value: '${nodes.review.output.json.summary}' },
    ]);
  });

  it('preserves the graph through the editor adapter and returns independent definitions', () => {
    const flow = definitionToFlow(definition);
    const roundTrip = flowToDefinition(flow.nodes, flow.edges, definition);
    expect(roundTrip.nodes.map((node) => node.key)).toEqual(definition.nodes.map((node) => node.key));
    expect(roundTrip.edges).toHaveLength(definition.edges.length);
    expect(buildAiReviewTemplate()).toEqual(definition);
    expect(buildAiReviewTemplate().nodes[1]).not.toBe(review);
  });
});
