import React, { useMemo } from 'react';
import { theme } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, AuditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface ReviewResultCardProps {
  content: string;
}

type ReviewVerdict = 'pass' | 'fail' | 'partial';

const VERDICT_CONFIG: Record<ReviewVerdict, { color: string; icon: React.ReactNode; key: string }> = {
  pass: { color: '#52c41a', icon: <CheckCircleOutlined />, key: 'review.pass' },
  fail: { color: '#ff4d4f', icon: <CloseCircleOutlined />, key: 'review.fail' },
  partial: { color: '#faad14', icon: <ExclamationCircleOutlined />, key: 'review.partial' },
};

const ReviewResultCard: React.FC<ReviewResultCardProps> = ({ content }) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  const verdict = useMemo<ReviewVerdict>(() => {
    const upper = content.toUpperCase();
    if (upper.includes('FAIL')) return 'fail';
    if (upper.includes('PARTIAL')) return 'partial';
    return 'pass';
  }, [content]);

  const config = VERDICT_CONFIG[verdict];

  return (
    <div style={{
      marginBottom: 10,
      padding: '8px 12px',
      borderRadius: 6,
      border: `1px solid ${config.color}33`,
      background: `${config.color}08`,
    }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        marginBottom: 6,
        fontSize: 13,
        fontWeight: 600,
        color: config.color,
      }}>
        <AuditOutlined />
        {t('review.title')}
        <span style={{ marginLeft: 'auto', fontSize: 12 }}>
          {config.icon} {t(config.key)}
        </span>
      </div>
      <div style={{
        fontSize: 12,
        color: token.colorText,
        lineHeight: 1.6,
        whiteSpace: 'pre-wrap',
      }}>
        {content}
      </div>
    </div>
  );
};

export default ReviewResultCard;
