import React from 'react';
import { Button, Spin, Empty, Popconfirm, Tooltip, theme } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AiChatSession } from '../../../types';

interface ChatSidebarProps {
  sessions: AiChatSession[];
  currentSessionId: string | null;
  loadingSessions: boolean;
  onSelectSession: (id: string) => void;
  onNewChat: () => void;
  onDeleteSession: (id: string) => void;
}

const ChatSidebar: React.FC<ChatSidebarProps> = ({
  sessions, currentSessionId, loadingSessions,
  onSelectSession, onNewChat, onDeleteSession,
}) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  return (
    <div 
      className="ai-chat-sidebar"
      style={{
        background: token.colorBgContainer,
        borderRight: `1px solid ${token.colorBorderSecondary}`,
      }}
    >
      <div style={{ padding: 16, flexShrink: 0 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          block
          onClick={onNewChat}
        >
          {t('ai.chat.new_chat')}
        </Button>
      </div>

      <div className="ai-chat-sidebar-list">
        {loadingSessions ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin size="small" />
          </div>
        ) : sessions.length === 0 ? (
          <Empty description={t('chat.noConversations')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          sessions.map(session => (
            <div
              key={session.id}
              onClick={() => onSelectSession(session.id)}
              style={{
                padding: '10px 12px',
                borderRadius: 8,
                cursor: 'pointer',
                marginBottom: 4,
                background: currentSessionId === session.id ? token.colorPrimaryBg : 'transparent',
                border: currentSessionId === session.id
                  ? `1px solid ${token.colorPrimaryBorder}`
                  : '1px solid transparent',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                transition: 'all 0.2s',
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                  fontSize: 14,
                  fontWeight: 500,
                  color: token.colorText,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}>
                  {session.title}
                </div>
                <div style={{
                  fontSize: 12,
                  color: token.colorTextSecondary,
                  marginTop: 2,
                }}>
                  {session.updatedAt}
                </div>
              </div>
              <Popconfirm
                title={t('chat.deleteConfirm')}
                onConfirm={(e) => { e?.stopPropagation(); onDeleteSession(session.id); }}
                onCancel={(e) => e?.stopPropagation()}
                okText={t('common.delete')}
                cancelText={t('common.cancel')}
              >
                <Tooltip title={t('common.delete')}>
                  <DeleteOutlined
                    onClick={(e) => e.stopPropagation()}
                    style={{
                      fontSize: 14,
                      color: token.colorTextTertiary,
                      opacity: 0.6,
                      padding: 4,
                    }}
                  />
                </Tooltip>
              </Popconfirm>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default ChatSidebar;
