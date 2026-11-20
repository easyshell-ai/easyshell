import { useEffect, useState } from 'react';
import { Alert, Button, Card, Select, Space, Spin, Table, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getHostAclCatalog, getHostPermissions, grantHostPermission, revokeHostPermission } from '../../api/hostPermissions';
import { getUserList } from '../../api/system';
import type { HostCatalogItem, HostGrant, HostPermission, PrincipalType } from '../../api/hostPermissions';

interface CatalogItem { id: string; name: string; role?: string }
interface GrantSnapshot { agentId: string; generation: number; grants: HostGrant[] }

function errorKey(error: unknown, fallback: string): string {
  const value = error as { code?: number; response?: { status?: number } } | null;
  const status = value?.response?.status ?? value?.code;
  return status === 403 ? 'hostPermissions.forbidden' : status === 409 ? 'hostPermissions.stale' : fallback;
}

async function loadHosts(): Promise<HostCatalogItem[]> {
  const hosts: HostCatalogItem[] = [];
  const seen = new Set<string>();
  for (let page = 0; page <= 10000; page++) {
    const res = await getHostAclCatalog(page, 100);
    if (res.code !== 200) throw res;
    if (!Array.isArray(res.data) || res.data.some((host) => !host.agentId || !Number.isSafeInteger(host.generation) || host.generation <= 0)) throw new Error('Invalid host catalog');
    for (const host of res.data) {
      // Offset pagination can repeat a host when the catalog changes mid-read.
      // Fail closed rather than choose an ambiguous generation or hide a missing host.
      if (seen.has(host.agentId)) throw { code: 409 };
      seen.add(host.agentId);
      hosts.push(host);
    }
    if (res.data.length < 100) return hosts;
  }
  throw new Error('Host catalog exceeds pagination limit');
}

export default function HostPermissions() {
  const { t } = useTranslation();
  const [hosts, setHosts] = useState<HostCatalogItem[]>();
  const [users, setUsers] = useState<CatalogItem[]>();
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState<string>();
  const [catalogReload, setCatalogReload] = useState(0);
  const [hostId, setHostId] = useState<string>();
  const [type, setType] = useState<PrincipalType>('USER');
  const [principalId, setPrincipalId] = useState<string>();
  const [permission, setPermission] = useState<HostPermission>('VIEW');
  const [snapshot, setSnapshot] = useState<GrantSnapshot>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [reload, setReload] = useState(0);
  const selectedHost = hosts?.find((host) => host.agentId === hostId);
  const generation = selectedHost?.generation;
  const grants = snapshot && snapshot.agentId === hostId && snapshot.generation === generation ? snapshot.grants : undefined;
  const roles = ['viewer', 'operator', 'admin', 'super_admin'].map((id) => ({ id, name: id }));
  const viewerSelected = type === 'ROLE' ? principalId === 'viewer' : users?.find((user) => user.id === principalId)?.role === 'viewer';

  useEffect(() => {
    let active = true;
    setCatalogLoading(true);
    setCatalogError(undefined);
    setHosts(undefined);
    setUsers(undefined);
    setSnapshot(undefined);
    Promise.all([loadHosts(), getUserList()]).then(([catalog, res]) => {
      if (res.code !== 200) throw res;
      if (!Array.isArray(res.data)) throw new Error('Invalid user catalog');
      if (active) {
        setHosts(catalog);
        setUsers(res.data.map((user) => ({ id: String(user.id), name: user.username, role: user.role })));
      }
    }).catch((err: unknown) => { if (active) setCatalogError(errorKey(err, 'hostPermissions.catalogError')); })
      .finally(() => { if (active) setCatalogLoading(false); });
    return () => { active = false; };
  }, [catalogReload]);

  useEffect(() => {
    let active = true;
    setSnapshot(undefined);
    setError(undefined);
    if (!hostId || !generation || catalogLoading || catalogError) { setLoading(false); return () => { active = false; }; }
    setLoading(true);
    (async () => {
      const res = await getHostPermissions(hostId);
      if (res.code !== 200) throw res;
      if (!Array.isArray(res.data)) throw new Error('Invalid grant list');
      const current = await loadHosts();
      if (current.find((host) => host.agentId === hostId)?.generation !== generation) {
        throw { code: 409 };
      }
      if (active) setSnapshot({ agentId: hostId, generation, grants: res.data });
    })().catch((err: unknown) => { if (active) setError(errorKey(err, 'hostPermissions.loadError')); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [hostId, generation, reload, catalogLoading, catalogError]);

  const refresh = () => { setHostId(undefined); setError(undefined); setCatalogReload((value) => value + 1); };
  const change = async (grant: HostGrant, revoke: boolean) => {
    if (!hostId || !generation || saving || !grants || snapshot?.agentId !== hostId || snapshot.generation !== generation) return;
    setSaving(true);
    setError(undefined);
    try {
      const request = { ...grant, expectedGeneration: generation };
      const res = await (revoke ? revokeHostPermission(hostId, request) : grantHostPermission(hostId, request));
      if (res.code !== 200) throw res;
      setSnapshot(undefined);
      setReload((value) => value + 1);
    } catch (err) {
      const status = (err as { code?: number; response?: { status?: number } } | null)?.response?.status ?? (err as { code?: number } | null)?.code;
      const key = !revoke && status === 400 ? 'hostPermissions.invalidGrant' : errorKey(err, 'hostPermissions.saveError');
      if (key === 'hostPermissions.stale') setSnapshot(undefined);
      setError(key);
    } finally {
      setSaving(false);
    }
  };

  const disabled = saving || loading || catalogLoading || Boolean(catalogError) || !grants || Boolean(error);
  const permissions: HostPermission[] = ['VIEW', 'EXECUTE', 'TERMINAL'];
  const labelPermission = (value: HostPermission) => t(`hostPermissions.${value.toLowerCase()}`);
  const columns = [
    { title: t('hostPermissions.principalType'), render: (_: unknown, grant: HostGrant) => t(grant.subjectType === 'USER' ? 'hostPermissions.user' : 'hostPermissions.role') },
    { title: t('hostPermissions.principal'), render: (_: unknown, grant: HostGrant) => {
      const item = (grant.subjectType === 'USER' ? users : roles)?.find((entry) => entry.id === grant.subjectId);
      return item ? `${item.name} (${grant.subjectId})` : grant.subjectId;
    } },
    { title: t('hostPermissions.permission'), render: (_: unknown, grant: HostGrant) => labelPermission(grant.permission) },
    { title: t('common.actions'), render: (_: unknown, grant: HostGrant) => <Button disabled={disabled} onClick={() => change(grant, true)}>{t('hostPermissions.revoke')}</Button> },
  ];

  return <Card title={t('hostPermissions.title')}>
    <Space orientation="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon title={t('hostPermissions.inheritanceNotice')} />
      <Alert type="info" showIcon title={t('hostPermissions.independentPermissionsNotice')} />
      <Alert type="warning" showIcon title={t('hostPermissions.terminalWarning')} />
      <Typography.Paragraph>{t('hostPermissions.explicitHostsNotice')}</Typography.Paragraph>
      {catalogError && <Alert type="error" title={t(catalogError)} action={<Button onClick={refresh}>{t('hostPermissions.retry')}</Button>} />}
      <Select aria-label={t('hostPermissions.host')} showSearch optionFilterProp="label" placeholder={t('hostPermissions.selectHost')} style={{ minWidth: 240 }} value={hostId} disabled={saving || catalogLoading || Boolean(catalogError)} onChange={setHostId} options={(hosts ?? []).map((item) => ({ value: item.agentId, label: `${item.hostname || item.agentId} (${item.agentId})` }))} />
      {error && <Alert type="error" title={t(error)} action={!saving && <Button onClick={error === 'hostPermissions.stale' ? refresh : () => setReload((value) => value + 1)}>{t('hostPermissions.reload')}</Button>} />}
      <Spin spinning={loading || catalogLoading}>
        {grants && <Table<HostGrant> rowKey={(grant) => JSON.stringify([grant.subjectType, grant.subjectId, grant.permission])} columns={columns} dataSource={grants} pagination={false} />}
      </Spin>
      <Space wrap>
        <Select aria-label={t('hostPermissions.principalType')} value={type} disabled={disabled} onChange={(value: PrincipalType) => { setType(value); setPrincipalId(undefined); }} options={[{ value: 'USER', label: t('hostPermissions.user') }, { value: 'ROLE', label: t('hostPermissions.role') }]} />
        <Select aria-label={t('hostPermissions.principal')} showSearch optionFilterProp="label" placeholder={t('hostPermissions.selectPrincipal')} style={{ minWidth: 240 }} value={principalId} disabled={disabled} onChange={setPrincipalId} options={(type === 'USER' ? users ?? [] : roles).map((item) => ({ value: item.id, label: `${item.name} (${item.id})` }))} />
        <Select aria-label={t('hostPermissions.permission')} value={permission} disabled={disabled} onChange={setPermission} options={permissions.map((value) => ({ value, label: labelPermission(value), disabled: viewerSelected && value === 'TERMINAL' }))} />
        <Button type="primary" disabled={disabled || !hostId || !principalId || (viewerSelected && permission === 'TERMINAL')} loading={saving} onClick={() => principalId && change({ subjectType: type, subjectId: principalId, permission }, false)}>{t('hostPermissions.grant')}</Button>
      </Space>
      {viewerSelected && <Alert type="info" showIcon title={t('hostPermissions.viewerTerminalForbidden')} />}
    </Space>
  </Card>;
}
