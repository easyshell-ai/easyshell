import React from 'react';
import { Tag, theme } from 'antd';
import { FileProtectOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface SopSuggestionCardProps {
  title?: string;
  description?: string;
  confidence?: number;
}

const SopSuggestionCard: React.FC<SopSuggestionCardProps> = ({ title, description, confidence }) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  return (
    <div style={{
      marginBottom: 10,
      padding: '8px 12px',
      background: 'rgba(82,196,26,0.04)',
      borderRadius: 8,
      border: '1px solid rgba(82,196,26,0.15)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        <FileProtectOutlined style={{ color: '#52c41a', fontSize: 14 }} />
        <span style={{ fontSize: 12, fontWeight: 600, color: '#52c41a' }}>
          {t('ai.chat.sop_matched')}
        </span>
        {confidence != null && (
          <Tag color="green" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', margin: 0 }}>
            {Math.round(confidence * 100)}%
          </Tag>
        )}
      </div>
      {title && (
        <div style={{ fontSize: 13, fontWeight: 500, color: token.colorText, marginBottom: 2 }}>
          {title}
        </div>
      )}
      {description && (
        <div style={{ fontSize: 12, color: token.colorTextSecondary, lineHeight: 1.5 }}>
          {description}
        </div>
      )}
    </div>
  );
};

export default SopSuggestionCard;
