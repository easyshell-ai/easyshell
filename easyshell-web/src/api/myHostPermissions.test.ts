import { describe, expect, it, vi } from 'vitest';
import request from './request';
import { getExecuteTargets, queryMyHostPermissions } from './myHostPermissions';

vi.mock('./request', () => ({ default: { post: vi.fn(), get: vi.fn() } }));

describe('my host permissions API', () => {
  it('queries the current user for the requested agent IDs', () => {
    queryMyHostPermissions(['host/1', 'host-2']);
    expect(request.post).toHaveBeenCalledWith('/v1/host-permissions/me/query', { agentIds: ['host/1', 'host-2'] });
  });

  it('requests bounded execute target pages with an optional cursor', () => {
    getExecuteTargets();
    expect(request.get).toHaveBeenLastCalledWith('/v1/host-permissions/me/execute-targets', { params: { limit: 100 } });
    getExecuteTargets('next+/=');
    expect(request.get).toHaveBeenLastCalledWith('/v1/host-permissions/me/execute-targets', { params: { limit: 100, cursor: 'next+/=' } });
  });
});
