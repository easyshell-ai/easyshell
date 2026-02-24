import request from './request';
import type { ApiResponse, TagVO, TagRequest } from '../types';

export function getTagList(): Promise<ApiResponse<TagVO[]>> {
  return request.get('/v1/tag/list');
}

export function createTag(data: TagRequest): Promise<ApiResponse<TagVO>> {
  return request.post('/v1/tag', data);
}

export function updateTag(id: number, data: TagRequest): Promise<ApiResponse<TagVO>> {
  return request.put(`/v1/tag/${id}`, data);
}

export function deleteTag(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/tag/${id}`);
}

export function addTagToAgent(tagId: number, agentId: string): Promise<ApiResponse<void>> {
  return request.post(`/v1/tag/${tagId}/agent/${agentId}`);
}

export function removeTagFromAgent(tagId: number, agentId: string): Promise<ApiResponse<void>> {
  return request.delete(`/v1/tag/${tagId}/agent/${agentId}`);
}

export function getAgentTags(agentId: string): Promise<ApiResponse<TagVO[]>> {
  return request.get(`/v1/tag/agent/${agentId}`);
}
