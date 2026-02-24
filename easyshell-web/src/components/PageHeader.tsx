import React from 'react';
import { Typography, Space, theme } from 'antd';

interface PageHeaderProps {
  /** Page title text */
  title: string;
  /** Optional icon before the title */
  icon?: React.ReactNode;
  /** Optional actions rendered on the right */
  actions?: React.ReactNode;
}

/**
 * Consistent page header with title + optional icon and action buttons.
 */
const PageHeader: React.FC<PageHeaderProps> = ({ title, icon, actions }) => {
  const { token } = theme.useToken();

  return (
    <div
      style={{
        marginBottom: 16,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
    >
      <Typography.Title level={4} style={{ margin: 0, color: token.colorText }}>
        {icon && (
          <span style={{ marginRight: 8, color: token.colorPrimary }}>{icon}</span>
        )}
        {title}
      </Typography.Title>
      {actions && <Space>{actions}</Space>}
    </div>
  );
};

export default PageHeader;
