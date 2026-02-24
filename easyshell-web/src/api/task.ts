import request from './request';
import type { ApiResponse, Task, TaskCreateRequest, TaskDetail, PageResponse } from '../types';

export function createTask(data: TaskCreateRequest): Promise<ApiResponse<Task>> {
  return request.post('/v1/task', data);
}

export function getTaskList(): Promise<ApiResponse<Task[]>> {
  return request.get('/v1/task/list');
}

export function getTaskPage(params: { status?: number; page?: number; size?: number }): Promise<ApiResponse<PageResponse<Task>>> {
  return request.get('/v1/task/page', { params });
}

export function getTaskDetail(taskId: string): Promise<ApiResponse<TaskDetail>> {
  return request.get(`/v1/task/${taskId}`);
}
