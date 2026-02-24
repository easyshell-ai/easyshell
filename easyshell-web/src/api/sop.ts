import request from './request';
import type { ApiResponse, PageResponse, AiSopTemplate, AiSopTemplateRequest } from '../types';

export function getSopList(page = 0, size = 20, category?: string): Promise<ApiResponse<PageResponse<AiSopTemplate>>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (category) params.append('category', category);
  return request.get(`/sop?${params.toString()}`);
}

export function getSop(id: number): Promise<ApiResponse<AiSopTemplate>> {
  return request.get(`/sop/${id}`);
}

export function updateSop(id: number, data: AiSopTemplateRequest): Promise<ApiResponse<AiSopTemplate>> {
  return request.put(`/sop/${id}`, data);
}

export function deleteSop(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/sop/${id}`);
}

export function triggerSopExtraction(): Promise<ApiResponse<string>> {
  return request.post('/sop/extract');
}
