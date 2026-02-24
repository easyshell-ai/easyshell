import request from './request';
import type { ApiResponse, ClusterVO, ClusterDetailVO, ClusterRequest } from '../types';

export function getClusterList(): Promise<ApiResponse<ClusterVO[]>> {
  return request.get('/v1/cluster/list');
}

export function getClusterDetail(id: number): Promise<ApiResponse<ClusterDetailVO>> {
  return request.get(`/v1/cluster/${id}`);
}

export function createCluster(data: ClusterRequest): Promise<ApiResponse<ClusterVO>> {
  return request.post('/v1/cluster', data);
}

export function updateCluster(id: number, data: ClusterRequest): Promise<ApiResponse<ClusterVO>> {
  return request.put(`/v1/cluster/${id}`, data);
}

export function deleteCluster(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/cluster/${id}`);
}

export function addClusterAgents(clusterId: number, agentIds: string[]): Promise<ApiResponse<void>> {
  return request.post(`/v1/cluster/${clusterId}/agents`, { agentIds });
}

export function removeClusterAgent(clusterId: number, agentId: string): Promise<ApiResponse<void>> {
  return request.delete(`/v1/cluster/${clusterId}/agents/${agentId}`);
}
