import { describe, expect, it } from 'vitest';
import { filterSensitive, sanitizeDefinitionForStorage, SENSITIVE_RULE_NAMES } from './sensitiveFilter';
import type { WorkflowDefinition } from '../types';

describe('filterSensitive (mirrors the server SensitiveDataFilter rules)', () => {
  it('covers all eight server rules', () => {
    expect(SENSITIVE_RULE_NAMES).toEqual([
      'key-value',
      'bearer',
      'openai-key',
      'aws-key',
      'private-key',
      'connection-string',
      'long-token',
      'export-env',
    ]);
  });

  it('masks key-value secrets and normalises the delimiter to =', () => {
    expect(filterSensitive('password=hunter2')).toBe('password=***');
    expect(filterSensitive('passwd: hunter2')).toBe('passwd=***');
    // 与 Java 一致：值组不含引号，收尾引号不在匹配范围内，原样留下
    expect(filterSensitive('pwd = "s3cret"')).toBe('pwd=***"');
    expect(filterSensitive('API_KEY=abcdef')).toBe('API_KEY=***');
    expect(filterSensitive('auth_token: abc.def')).toBe('auth_token=***');
  });

  it('keeps the Bearer prefix but drops the token', () => {
    expect(filterSensitive('Authorization: Bearer abcDEF123.456-_~')).toBe('Authorization: Bearer ***');
  });

  it('masks provider keys and connection strings', () => {
    expect(filterSensitive('sk-' + 'a'.repeat(24))).toBe('sk-***');
    expect(filterSensitive('AKIA' + 'A'.repeat(16))).toBe('AKIA***');
    expect(filterSensitive('mysql://user:sup3rsecret@db:3306/x')).toBe('mysql://user:***@db:3306/x');
    expect(filterSensitive('jdbc:postgresql://user:pw@host/db')).toBe('jdbc:postgresql://user:***@host/db');
  });

  it('replaces a whole PEM block', () => {
    const pem = '-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----';
    expect(filterSensitive(pem)).toBe('-----BEGIN PRIVATE KEY-----\n***\n-----END PRIVATE KEY-----');
  });

  it('masks exported env values while keeping the variable name', () => {
    expect(filterSensitive('export DB_PASSWORD=letmein')).toBe('export DB_PASSWORD=***');
    expect(filterSensitive('export MY_TOKEN="abc"')).toBe('export MY_TOKEN=***"');
  });

  it('leaves ordinary shell content untouched', () => {
    const script = "#!/bin/bash\nsystemctl status nginx | grep Active\n";
    expect(filterSensitive(script)).toBe(script);
  });
});

describe('sanitizeDefinitionForStorage', () => {
  it('scrubs credentials nested in node config before they reach localStorage', () => {
    const definition: WorkflowDefinition = {
      schemaVersion: 1,
      defaultOnUpstreamFailure: 'skip',
      onRejection: 'abort',
      variables: {},
      nodes: [
        {
          key: 'n1',
          type: 'script-exec',
          name: 'deploy',
          position: { x: 0, y: 0 },
          config: { scriptContent: 'curl -H "Authorization: Bearer abcDEF123.456" https://x' },
        },
      ],
      edges: [],
    };
    const stored = sanitizeDefinitionForStorage(definition);
    expect(stored).not.toContain('abcDEF123.456');
    expect(stored).toContain('Bearer ***');
    expect(stored).toContain('"key":"n1"');
    expect(() => JSON.parse(stored)).not.toThrow();
  });

  it('round-trips a clean definition unchanged', () => {
    const definition: WorkflowDefinition = {
      schemaVersion: 1,
      defaultOnUpstreamFailure: 'skip',
      onRejection: 'abort',
      variables: { env: { type: 'string', required: true } },
      nodes: [{ key: 'n1', type: 'script-exec', name: 'x', position: { x: 1, y: 2 }, config: { scriptContent: 'ls -la' } }],
      edges: [],
    };
    expect(sanitizeDefinitionForStorage(definition)).toBe(JSON.stringify(definition));
  });
});
