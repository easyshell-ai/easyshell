import React from 'react';
import { theme } from 'antd';
import { BulbOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface MemoryCardProps {
  content?: string;
}

const MemoryCard: React.FC<MemoryCardProps> = ({ content }) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  return (
    <div style={{
      marginBottom: 10,
      padding: '8px 12px',
      background: 'rgba(114,46,209,0.04)',
      borderRadius: 8,
      border: '1px solid rgba(114,46,209,0.15)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: content ? 6 : 0 }}>
        <BulbOutlined style={{ color: '#722ed1', fontSize: 14 }} />
        <span style={{ fontSize: 12, fontWeight: 600, color: '#722ed1' }}>
          {t('ai.chat.memory_retrieved')}
        </span>
      </div>
      {content ? (
        <div style={{
          fontSize: 12,
          color: token.colorTextSecondary,
          lineHeight: 1.5,
          maxHeight: 80,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}>
          {content.length > 200 ? content.slice(0, 200) + '...' : content}
        </div>
      ) : (
        <span style={{ fontSize: 12, color: token.colorTextTertiary, fontStyle: 'italic' }}>
          {t('ai.chat.memory_none')}
        </span>
      )}
    </div>
  );
};

export default MemoryCard;
