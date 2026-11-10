import { describe, it, expect, beforeEach } from 'vitest';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import { validateWorkflow } from './api';
import type { WorkflowDefinition } from './types';

/**
 * 回归：POST /{id}/validate 曾不带请求体，服务端的 @RequestBody 因此抛
 * HttpMessageNotReadableException → 500，而调用方把非 200 一律当成"无 issues"，
 * 于是发布弹窗把 ERROR 工作流显示成"校验通过"并放行发布按钮。
 *
 * 这里用 axios adapter 捕获真实发出的请求，断言请求体里真的带着 definition。
 */
function capturingAdapter(
  sink: { url: string; method: string; body: unknown }[]
): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    sink.push({
      url: config.url || '',
      method: (config.method || '').toUpperCase(),
      body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    });
    return Promise.resolve({
      data: { code: 200, message: 'ok', data: { valid: true, issues: [] } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    });
  };
}

const definition: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [
    { key: 'n1', type: 'script-exec', name: 'first', position: { x: 0, y: 0 }, config: { script: 'ls' } },
  ],
  edges: [],
};

describe('validateWorkflow', () => {
  let captured: { url: string; method: string; body: unknown }[];

  beforeEach(() => {
    captured = [];
  });

  it('POSTs the draft definition in the request body', async () => {
    await validateWorkflow(7, definition, { adapter: capturingAdapter(captured) });

    expect(captured).toHaveLength(1);
    expect(captured[0].method).toBe('POST');
    expect(captured[0].url).toBe('/v1/workflow/7/validate');
    expect(captured[0].body).toEqual({ definition });
  });

  it('sends the definition the caller passes, not a stale copy', async () => {
    const edited: WorkflowDefinition = {
      ...definition,
      nodes: [{ key: 'n1', type: 'script-exec', name: 'first', position: { x: 0, y: 0 }, config: { script: 'rm -rf /' } }],
    };

    await validateWorkflow(7, edited, { adapter: capturingAdapter(captured) });

    // 编辑器传的是本地草稿（含未保存改动）：请求体必须与入参逐字一致
    expect(captured[0].body).toEqual({ definition: edited });
  });
});
