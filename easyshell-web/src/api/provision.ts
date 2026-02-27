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

export function batchDeploy(credentialIds: number[]): Promise<ApiResponse<HostCredentialVO[]>> {
  return request.post('/v1/host/provision/deploy/batch', credentialIds);
}

export function importCsv(file: File): Promise<ApiResponse<HostCredentialVO[]>> {
  const formData = new FormData();
  formData.append('file', file);
  return request.post('/v1/host/provision/import/csv', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export async function downloadTemplate(): Promise<void> {
  const blob = await request.get('/v1/host/provision/import/template', { responseType: 'blob' });
  const url = URL.createObjectURL(blob as unknown as Blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'host_import_template.csv';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

export function getUnifiedHostList(): Promise<ApiResponse<HostCredentialVO[]>> {
  return request.get('/v1/host/provision/unified-list');
}

export function reinstallByCredentialId(credentialId: number): Promise<ApiResponse<HostCredentialVO>> {
  return request.post(`/v1/host/provision/reinstall/credential/${credentialId}`);
}