import { describe, expect, it } from 'vitest';
import { clearDraft, draftKey, readDraft, writeDraft, DRAFT_KEY_PREFIX } from './draftStorage';
import type { WorkflowDefinition } from '../types';

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (key: string) => (map.has(key) ? map.get(key)! : null),
    key: (index: number) => [...map.keys()][index] ?? null,
    removeItem: (key: string) => map.delete(key),
    setItem: (key: string, value: string) => {
      map.set(key, value);
    },
  } as Storage;
}

const definition: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [],
  edges: [],
};

describe('draftStorage', () => {
  it('uses the wf-draft-{id} key namespace', () => {
    expect(DRAFT_KEY_PREFIX).toBe('wf-draft-');
    expect(draftKey(42)).toBe('wf-draft-42');
  });

  it('round-trips a clean draft', () => {
    const storage = memoryStorage();
    writeDraft(storage, 7, definition, 3, 1000);
    const back = readDraft(storage, 7);
    expect(back?.baseVersion).toBe(3);
    expect(back?.savedAt).toBe(1000);
    expect(back?.definition.nodes).toEqual([]);
  });

  it('scrubs credentials before they are stored', () => {
    const storage = memoryStorage();
    const dirty = {
      ...definition,
      nodes: [
        {
          key: 'n1',
          type: 'script-exec',
          name: 'deploy',
          position: { x: 0, y: 0 },
          config: { script: 'mysql://root:s3cret@db/app' },
        },
      ],
    } as unknown as WorkflowDefinition;
    writeDraft(storage, 7, dirty, 1, 1000);
    const raw = storage.getItem(draftKey(7))!;
    expect(raw).not.toContain('s3cret');
    expect(raw).toContain('***');
    expect(readDraft(storage, 7)?.definition.nodes[0].config.script).toBe('mysql://root:***@db/app');
  });

  it('returns null for missing or corrupt entries', () => {
    const storage = memoryStorage();
    expect(readDraft(storage, 99)).toBeNull();
    storage.setItem(draftKey(99), '{not json');
    expect(readDraft(storage, 99)).toBeNull();
  });

  it('clears the entry', () => {
    const storage = memoryStorage();
    writeDraft(storage, 5, definition, 1, 1);
    clearDraft(storage, 5);
    expect(readDraft(storage, 5)).toBeNull();
  });
});
