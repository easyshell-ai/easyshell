import React from 'react';
import {
  AuditOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  NotificationOutlined,
  PlayCircleOutlined,
  RobotOutlined,
  StopOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import type { WorkflowNodeType } from '../types';

const ICONS: Record<WorkflowNodeType, React.ReactNode> = {
  start: <PlayCircleOutlined />,
  'script-exec': <CodeOutlined />,
  transform: <SwapOutlined />,
  condition: <BranchesOutlined />,
  'ai-analyze': <RobotOutlined />,
  approval: <AuditOutlined />,
  notify: <NotificationOutlined />,
  wait: <ClockCircleOutlined />,
  end: <StopOutlined />,
};

/** 9 类节点的唯一图标映射（调色板 / 检查器 / 帮助共用） */
export const NodeTypeIcon: React.FC<{ type: WorkflowNodeType }> = ({ type }) => <>{ICONS[type]}</>;

export default NodeTypeIcon;
