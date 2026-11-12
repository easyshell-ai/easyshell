import { describe, expect, it } from 'vitest';
import { draftKey, DRAFT_KEY_PREFIX } from './draftStorage';

describe('legacy draft keys', () => {
  it('identifies the unowned legacy namespace without accessing storage', () => {
    expect(DRAFT_KEY_PREFIX).toBe('wf-draft-');
    expect(draftKey(42)).toBe('wf-draft-42');
  });
});
