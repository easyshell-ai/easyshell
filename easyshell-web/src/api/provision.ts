import request from './request';
import type { ApiResponse, HostProvisionRequest, HostCredentialVO } from '../types';

export function provisionHost(data: HostProvisionRequest): Promise<ApiResponse<HostCredentialVO>> {
  return request.post('/v1/host/provision', data);
}

export function getProvisionList(): Promise<ApiResponse<HostCredentialVO[]>> {
  return request.get('/v1/host/provision/list');
}

export function getProvisionById(id: number): Promise<ApiResponse<HostCredentialVO>> {
  return request.get(`/v1/host/provision/${id}`);
}

export function deleteProvision(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/host/provision/${id}`);
}

export function retryProvision(id: number): Promise<ApiResponse<HostCredentialVO>> {
  return request.post(`/v1/host/provision/${id}/retry`);
}

export function reinstallAgent(agentId: string): Promise<ApiResponse<HostCredentialVO>> {
  return request.post(`/v1/host/provision/reinstall/${agentId}`);
}

export function batchReinstallAgents(agentIds: string[]): Promise<ApiResponse<HostCredentialVO[]>> {
  return request.post('/v1/host/provision/reinstall/batch', agentIds);
}


export function uninstallAgent(agentId: string): Promise<ApiResponse<HostCredentialVO>> {
  return request.post(`/v1/host/provision/uninstall/${agentId}`);
}