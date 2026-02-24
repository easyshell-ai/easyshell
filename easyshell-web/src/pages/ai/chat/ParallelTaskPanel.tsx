import React from 'react';
import { Progress, theme } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface ParallelTaskPanelProps {
  groups: Array<{ group: number; completed: number; total: number }>;
}

const ParallelTaskPanel: React.FC<ParallelTaskPanelProps> = ({ groups }) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  if (groups.length === 0) return null;

  return (
    <div style={{
      marginBottom: 10,
      padding: '8px 10px',
      background: 'rgba(22,119,255,0.04)',
      borderRadius: 6,
      border: '1px solid rgba(22,119,255,0.15)',
    }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        marginBottom: 6,
        fontSize: 12,
        fontWeight: 600,
        color: token.colorPrimary,
      }}>
        <ThunderboltOutlined />
        {t('ai.chat.parallel_start')}
      </div>
      {groups.map(g => {
        const percent = g.total > 0 ? Math.round((g.completed / g.total) * 100) : 0;
        return (
          <div key={g.group} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <span style={{ fontSize: 11, color: token.colorTextSecondary, minWidth: 60 }}>
              {t('plan.parallelGroup', { group: g.group })}
            </span>
            <Progress
              percent={percent}
              size="small"
              style={{ flex: 1, margin: 0 }}
              strokeColor={percent === 100 ? '#52c41a' : token.colorPrimary}
            />
            <span style={{ fontSize: 11, color: token.colorTextSecondary, minWidth: 30 }}>
              {g.completed}/{g.total}
            </span>
          </div>
        );
      })}
    </div>
  );
};

export default ParallelTaskPanel;
