import request from './request';
import type { ApiResponse, Agent, DashboardStats, TagVO, Job, MetricSnapshot, HostSoftwareInventory } from '../types';

export function getHostList(): Promise<ApiResponse<Agent[]>> {
  return request.get('/v1/host/list');
}

export function getHostDetail(agentId: string): Promise<ApiResponse<Agent>> {
  return request.get(`/v1/host/${agentId}`);
}

export function getDashboardStats(): Promise<ApiResponse<DashboardStats>> {
  return request.get('/v1/host/dashboard/stats');
}

export function getAgentTags(agentId: string): Promise<ApiResponse<TagVO[]>> {
  return request.get(`/v1/host/${agentId}/tags`);
}

export function getAgentJobs(agentId: string): Promise<ApiResponse<Job[]>> {
  return request.get(`/v1/task/agent/${agentId}/jobs`);
}

export function getAgentMetrics(agentId: string, range: string): Promise<ApiResponse<MetricSnapshot[]>> {
  return request.get(`/v1/host/${agentId}/metrics`, { params: { range } });
}

export function triggerDetection(agentId: string): Promise<ApiResponse<string>> {
  return request.post(`/v1/hosts/${agentId}/detect`);
}

export function parseDetection(agentId: string, taskId: string): Promise<ApiResponse<HostSoftwareInventory[]>> {
  return request.post(`/v1/hosts/${agentId}/detect/${taskId}/parse`);
}

export function getHostSoftware(agentId: string): Promise<ApiResponse<HostSoftwareInventory[]>> {
  return request.get(`/v1/hosts/${agentId}/software`);
}

export function getDockerContainers(agentId: string): Promise<ApiResponse<HostSoftwareInventory[]>> {
  return request.get(`/v1/hosts/${agentId}/docker/containers`);
}

export function getHostInventory(agentId: string): Promise<ApiResponse<HostSoftwareInventory[]>> {
  return request.get(`/v1/hosts/${agentId}/inventory`);
}


export function deleteHost(agentId: string): Promise<ApiResponse<void>> {
  return request.delete(`/v1/host/${agentId}`);
}

export function deleteCredential(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/host/credential/${id}`);
}