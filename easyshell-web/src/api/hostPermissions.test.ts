import { describe, expect, it, vi } from 'vitest';
import request from './request';
import { getHostAclCatalog, getHostPermissions, grantHostPermission, revokeHostPermission } from './hostPermissions';

vi.mock('./request', () => ({ default: { get: vi.fn(), put: vi.fn(), delete: vi.fn() } }));

describe('host ACL API', () => {
  it('uses the host-scoped endpoints and a single grant body', () => {
    const grant = { subjectType: 'ROLE' as const, subjectId: 'operator', permission: 'TERMINAL' as const, expectedGeneration: 7 };
    getHostAclCatalog(1, 100);
    getHostPermissions('host/1');
    grantHostPermission('host/1', grant);
    revokeHostPermission('host/1', grant);
    expect(request.get).toHaveBeenCalledWith('/v1/host-acl/host%2F1');
    expect(request.get).toHaveBeenCalledWith('/v1/host-acl/catalog', { params: { page: 1, size: 100 } });
    expect(request.put).toHaveBeenCalledWith('/v1/host-acl/host%2F1', grant);
    expect(request.delete).toHaveBeenCalledWith('/v1/host-acl/host%2F1', { data: grant });
  });
});
