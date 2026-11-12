import { describe, expect, it } from 'vitest';
import {
  composeCondition,
  formatOperand,
  isAdvancedCondition,
  parseCondition,
} from './conditionBuilderModel';

describe('condition builder model', () => {
  it('composes paths, numbers, booleans and quoted literals', () => {
    expect(
      composeCondition({ left: 'nodes.check.output.usage', operator: '>', right: '90' })
    ).toBe('nodes.check.output.usage > 90');
    expect(
      composeCondition({ left: 'nodes.n1.output.state', operator: '==', right: 'running' })
    ).toBe("nodes.n1.output.state == 'running'");
    expect(
      composeCondition({ left: 'nodes.n1.output.ok', operator: '==', right: 'true' })
    ).toBe('nodes.n1.output.ok == true');
    expect(
      composeCondition({ left: 'nodes.a.output.x', operator: '==', right: 'nodes.b.output.y' })
    ).toBe('nodes.a.output.x == nodes.b.output.y');
    expect(
      composeCondition({ left: 'nodes.a.output.message', operator: 'contains', right: "it's here" })
    ).toBe("nodes.a.output.message contains 'it\\'s here'");
    expect(formatOperand("'already quoted'")).toBe("'already quoted'");
  });

  it('leaves the expression untouched while the structured form is incomplete', () => {
    expect(composeCondition({ left: '', operator: '==', right: '1' })).toBe('');
    expect(composeCondition({ left: 'nodes.a.output.x', operator: '==', right: '' })).toBe('');
  });

  it('parses a builder expression back into the same parts (round-trip)', () => {
    const parts = { left: 'nodes.check.output.usage', operator: '>=' as const, right: '85.5' };
    expect(parseCondition(composeCondition(parts))).toEqual(parts);

    const stringParts = { left: 'nodes.check.output.status', operator: '!=' as const, right: 'down' };
    expect(parseCondition(composeCondition(stringParts))).toEqual(stringParts);

    const variableParts = {
      left: 'nodes.check.output.x',
      operator: '==' as const,
      right: 'run.input.threshold',
    };
    expect(parseCondition(composeCondition(variableParts))).toEqual(variableParts);
  });

  it('rejects compound expressions so the raw textarea stays authoritative', () => {
    expect(parseCondition('nodes.a.output.x > 1 && nodes.b.output.y < 2')).toBeNull();
    expect(isAdvancedCondition('nodes.a.output.x > 1 && nodes.b.output.y < 2')).toBe(true);
    expect(composeCondition({ left: 'a.x', operator: '==', right: '1' })).toBe('a.x == 1');
    expect(parseCondition('a.x == 1')).toEqual({ left: 'a.x', operator: '==', right: '1' });
    expect(parseCondition('  ')).toBeNull();
  });
});
