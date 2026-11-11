import request from './request';
import type { ApiResponse } from '../types';

/** A point-in-time UI hint, not an authorization credential. */
export interface MyHostPermission {
  agentId: string;
  generation: number;
  view: boolean;
  execute: boolean;
  terminal: boolean;
}

export function queryMyHostPermissions(agentIds: string[]): Promise<ApiResponse<MyHostPermission[]>> {
  return request.post('/v1/host-permissions/me/query', { agentIds });
}

export interface ExecuteTargetsPage {
  agentIds: string[];
  nextCursor: string | null;
}

export function getExecuteTargets(cursor?: string): Promise<ApiResponse<ExecuteTargetsPage>> {
  return request.get('/v1/host-permissions/me/execute-targets', {
    params: cursor === undefined ? { limit: 100 } : { limit: 100, cursor },
  });
}
