import React, { useMemo } from 'react';
import { Card, Tooltip } from 'antd';
import {
  AuditOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  NotificationOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { HANDLE_CONTRACT } from '../graph';
import { CLIPBOARD_MIME } from './canvas/clipboard';
import type { WorkflowNodeType } from '../types';

/**
 * 节点调色板：只负责"添加节点"（设计 §4.2 双编辑器裁定——结构变更唯一入口是画布，
 * 列表面板不提供新增/删除之外的任何写路径）。
 */

const PALETTE: Array<{ type: WorkflowNodeType; icon: React.ReactNode }> = [
  { type: 'script-exec', icon: <CodeOutlined /> },
  { type: 'condition', icon: <BranchesOutlined /> },
  { type: 'notify', icon: <NotificationOutlined /> },
  { type: 'approval', icon: <AuditOutlined /> },
  { type: 'wait', icon: <ClockCircleOutlined /> },
];

const NodePalette: React.FC = () => {
  const { t } = useTranslation();

  const items = useMemo(
    () =>
      PALETTE.map(({ type, icon }) => ({
        type,
        icon,
        label: t(`workflow.node.type.${type}`),
        description: t(`workflow.node.desc.${type}`),
        handles: HANDLE_CONTRACT[type],
      })),
    [t]
  );

  return (
    <Card
      size="small"
      title={<span style={{ fontSize: 13 }}>{t('workflow.palette.title')}</span>}
      style={{ height: '100%' }}
      styles={{ body: { padding: 12 } }}
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {items.map((item) => (
          <Tooltip key={item.type} title={item.description} placement="right">
            <div
              className="wf-palette-item"
              draggable
              data-testid={`palette-${item.type}`}
              onDragStart={(event) => {
                event.dataTransfer.setData(CLIPBOARD_MIME, JSON.stringify({ type: item.type }));
                event.dataTransfer.effectAllowed = 'copy';
              }}
              role="button"
              tabIndex={0}
              aria-label={`${t('workflow.palette.add')} ${item.label}`}
              onKeyDown={(event) => {
                if (event.key !== 'Enter') return;
                // 键盘等价操作：HTML5 拖拽对键盘不可达，改派自定义事件，
                // 画布监听 wf:palette-drop 后走与 drop 完全相同的落点计算
                const canvas = document.querySelector('[data-testid="workflow-canvas"] .react-flow');
                if (!canvas) return;
                const rect = canvas.getBoundingClientRect();
                canvas.dispatchEvent(
                  new CustomEvent('wf:palette-drop', {
                    detail: {
                      type: item.type,
                      clientX: rect.left + rect.width * 0.3,
                      clientY: rect.top + rect.height * 0.4,
                    },
                    bubbles: true,
                  })
                );
              }}
            >
              <span className="wf-palette-icon">{item.icon}</span>
              <span className="wf-palette-body">
                <span className="wf-palette-label">{item.label}</span>
                <span className="wf-palette-meta">
                  {t('workflow.palette.handles', { in: item.handles.inputs, out: item.handles.outputs })}
                </span>
              </span>
            </div>
          </Tooltip>
        ))}
      </div>
      <div className="wf-palette-foot">{t('workflow.palette.hint')}</div>
    </Card>
  );
};

export default NodePalette;
