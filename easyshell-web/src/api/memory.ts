import request from './request';
import type { ApiResponse, PageResponse, AiSessionSummary } from '../types';

export function getMemoryList(page = 0, size = 20): Promise<ApiResponse<PageResponse<AiSessionSummary>>> {
  return request.get(`/memory?page=${page}&size=${size}`);
}

export function getMemory(id: number): Promise<ApiResponse<AiSessionSummary>> {
  return request.get(`/memory/${id}`);
}

export function deleteMemory(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/memory/${id}`);
}

export function clearAllMemory(): Promise<ApiResponse<null>> {
  return request.delete('/memory');
}
