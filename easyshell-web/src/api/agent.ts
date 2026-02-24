import request from './request';
import type { ApiResponse } from '../types';

export interface AgentDefinitionVO {
  id: number;
  name: string;
  displayName: string | null;
  mode: string;
  permissions: string;
  modelProvider: string | null;
  modelName: string | null;
  systemPrompt: string;
  maxIterations: number;
  enabled: boolean;
  description: string | null;
}

export interface AgentDefinitionRequest {
  name: string;
  displayName?: string;
  mode: string;
  permissions: string;
  modelProvider?: string;
  modelName?: string;
  systemPrompt: string;
  maxIterations?: number;
  enabled?: boolean;
  description?: string;
}

export function listAgents(): Promise<ApiResponse<AgentDefinitionVO[]>> {
  return request.get('/agents');
}

export function getAgent(id: number): Promise<ApiResponse<AgentDefinitionVO>> {
  return request.get(`/agents/${id}`);
}

export function createAgent(data: AgentDefinitionRequest): Promise<ApiResponse<AgentDefinitionVO>> {
  return request.post('/agents', data);
}

export function updateAgent(id: number, data: AgentDefinitionRequest): Promise<ApiResponse<AgentDefinitionVO>> {
  return request.put(`/agents/${id}`, data);
}

export function toggleAgent(id: number): Promise<ApiResponse<AgentDefinitionVO>> {
  return request.patch(`/agents/${id}/toggle`);
}

export function deleteAgent(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/agents/${id}`);
}

export interface AvailableTool {
  name: string;
  description: string;
}

export interface AvailableProvider {
  key: string;
  configured: boolean;
  model: string;
}

export function getAvailableTools(): Promise<ApiResponse<AvailableTool[]>> {
  return request.get('/agents/available-tools');
}

export function getAvailableProviders(): Promise<ApiResponse<AvailableProvider[]>> {
  return request.get('/agents/available-providers');
}
