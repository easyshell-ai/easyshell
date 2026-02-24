import React from 'react';
import { Tag, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  taskStatusMap,
  jobStatusMap,
  hostStatusMap,
  riskLevelMap,
  approvalStatusMap,
  taskTypeMap,
  softwareTypeMap,
  reportStatusMap,
  provisionStatusMap,
  type StatusEntry,
  type StatusEntryWithLabel,
} from '../utils/status';

type StatusMapType =
  | 'task'
  | 'job'
  | 'host'
  | 'risk'
  | 'approval'
  | 'taskType'
  | 'software'
  | 'report'
  | 'provision';

const statusMaps: Record<StatusMapType, Record<string | number, StatusEntry | StatusEntryWithLabel>> = {
  task: taskStatusMap,
  job: jobStatusMap,
  host: hostStatusMap,
  risk: riskLevelMap,
  approval: approvalStatusMap,
  taskType: taskTypeMap,
  software: softwareTypeMap,
  report: reportStatusMap,
  provision: provisionStatusMap,
};

interface StatusTagProps {
  type: StatusMapType;
  value: string | number | undefined | null;
  fallback?: string;
}

const StatusTag: React.FC<StatusTagProps> = ({ type, value, fallback = 'common.unknown' }) => {
  const { t } = useTranslation();

  if (value === undefined || value === null) {
    return <Tag>{t(fallback)}</Tag>;
  }

  const map = statusMaps[type];
  const entry = map[value];

  if (!entry) {
    return <Tag>{String(value)}</Tag>;
  }

  const text = 'text' in entry ? t(entry.text) : 'label' in entry ? t(entry.label) : String(value);
  const icon = 'icon' in entry ? (entry as StatusEntry).icon : undefined;

  return (
    <Tag color={entry.color}>
      {icon ? <Space size={4}>{icon}{text}</Space> : text}
    </Tag>
  );
};

export default StatusTag;
