import request from './request';
import type { ApiResponse, LoginRequest, LoginResponse, UserVO } from '../types';

export function login(data: LoginRequest): Promise<ApiResponse<LoginResponse>> {
  return request.post('/v1/auth/login', data);
}

export function refreshToken(token: string): Promise<ApiResponse<LoginResponse>> {
  return request.post('/v1/auth/refresh', { refreshToken: token });
}

export function getMe(): Promise<ApiResponse<UserVO>> {
  return request.get('/v1/auth/me');
}
