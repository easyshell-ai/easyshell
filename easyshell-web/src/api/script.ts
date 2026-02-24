import request from './request';
import type { ApiResponse, Script, ScriptRequest } from '../types';

export function getScriptList(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/list');
}

export function getScriptTemplates(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/templates');
}

export function getUserScripts(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/user-scripts');
}

export function getScript(id: number): Promise<ApiResponse<Script>> {
  return request.get(`/v1/script/${id}`);
}

export function createScript(data: ScriptRequest): Promise<ApiResponse<Script>> {
  return request.post('/v1/script', data);
}

export function updateScript(id: number, data: ScriptRequest): Promise<ApiResponse<Script>> {
  return request.put(`/v1/script/${id}`, data);
}

export function deleteScript(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/script/${id}`);
}
