import request from './request';
import type { ApiResponse, AuditLog, PageResponse } from '../types';

export function getAuditLogList(params: {
  userId?: number;
  resourceType?: string;
  action?: string;
  page?: number;
  size?: number;
}): Promise<ApiResponse<PageResponse<AuditLog>>> {
  return request.get('/v1/audit/list', { params });
}
