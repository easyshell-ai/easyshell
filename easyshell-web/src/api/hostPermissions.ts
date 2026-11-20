import request from './request';
import type { ApiResponse } from '../types';

export type PrincipalType = 'USER' | 'ROLE';
export type HostPermission = 'VIEW' | 'EXECUTE' | 'TERMINAL';
export interface HostGrant {
  subjectType: PrincipalType;
  subjectId: string;
  permission: HostPermission;
}
export interface HostCatalogItem { agentId: string; hostname: string | null; generation: number }
export interface HostGrantChange extends HostGrant { expectedGeneration: number }

export function getHostAclCatalog(page: number, size = 100): Promise<ApiResponse<HostCatalogItem[]>> {
  return request.get('/v1/host-acl/catalog', { params: { page, size } });
}

export function getHostPermissions(agentId: string): Promise<ApiResponse<HostGrant[]>> {
  return request.get(`/v1/host-acl/${encodeURIComponent(agentId)}`);
}

export function grantHostPermission(agentId: string, grant: HostGrantChange): Promise<ApiResponse<void>> {
  return request.put(`/v1/host-acl/${encodeURIComponent(agentId)}`, grant);
}

export function revokeHostPermission(agentId: string, grant: HostGrantChange): Promise<ApiResponse<void>> {
  return request.delete(`/v1/host-acl/${encodeURIComponent(agentId)}`, { data: grant });
}
