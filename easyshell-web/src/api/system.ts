import request from './request';
import type {
  ApiResponse,
  UserVO,
  UserCreateRequest,
  UserUpdateRequest,
  PasswordResetRequest,
  SystemConfigVO,
  SystemConfigRequest,
} from '../types';

export function getUserList(): Promise<ApiResponse<UserVO[]>> {
  return request.get('/v1/user/list');
}

export function getUserDetail(id: number): Promise<ApiResponse<UserVO>> {
  return request.get(`/v1/user/${id}`);
}

export function createUser(
  data: UserCreateRequest
): Promise<ApiResponse<UserVO>> {
  return request.post('/v1/user', data);
}

export function updateUser(
  id: number,
  data: UserUpdateRequest
): Promise<ApiResponse<UserVO>> {
  return request.put(`/v1/user/${id}`, data);
}

export function deleteUser(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/v1/user/${id}`);
}

export function resetUserPassword(
  id: number,
  data: PasswordResetRequest
): Promise<ApiResponse<null>> {
  return request.put(`/v1/user/${id}/password`, data);
}

export function getSystemConfigList(
  group?: string
): Promise<ApiResponse<SystemConfigVO[]>> {
  const params = group ? { group } : {};
  return request.get('/v1/system/config', { params });
}

export function saveSystemConfig(
  data: SystemConfigRequest
): Promise<ApiResponse<SystemConfigVO>> {
  return request.put('/v1/system/config', data);
}

export function deleteSystemConfig(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/v1/system/config/${id}`);
}
