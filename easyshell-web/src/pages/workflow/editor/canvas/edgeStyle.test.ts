import { describe, expect, it } from 'vitest';
import { edgePillOf } from './edgeStyle';

describe('edge pills', () => {
  it('renders classifier handles as class-name pills, never IF/ELSE', () => {
    const disk = edgePillOf('classifier', 'disk');
    expect(disk).toMatchObject({ kind: 'class', text: 'disk' });
    expect(disk.className).toContain('wf-edge-pill--class');

    const fallback = edgePillOf('classifier', 'default');
    expect(fallback.text).toBe('default');
    expect(fallback.className).toContain('wf-edge-pill--default');

    expect(edgePillOf('classifier', null).text).toBe('default');
  });

  it('keeps the condition IF/ELSE semantics unchanged', () => {
    expect(edgePillOf('condition', 'true')).toMatchObject({ kind: 'if', text: 'IF' });
    expect(edgePillOf('condition', 'false')).toMatchObject({ kind: 'else', text: 'ELSE' });
    expect(edgePillOf('condition', null, 'x > 1')).toMatchObject({ kind: 'legacy-if', text: 'IF' });
    expect(edgePillOf('script-exec', 'out').kind).toBe('none');
  });
});
