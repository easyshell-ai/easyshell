import { useState, useRef, useEffect } from 'react';
import { Tabs, Input, Tooltip } from 'antd';
import type { InputRef } from 'antd';
import { useTranslation } from 'react-i18next';
import type { TerminalTab } from '../types';
import { MAX_TABS, statusConfig } from '../types';

interface TerminalTabsProps {
  tabs: TerminalTab[];
  activeTabId: string;
  onTabChange: (tabId: string) => void;
  onTabClose: (tabId: string) => void;
  onTabAdd: () => void;
  onTabRename: (tabId: string, newLabel: string) => void;
}

const TerminalTabs: React.FC<TerminalTabsProps> = ({
  tabs,
  activeTabId,
  onTabChange,
  onTabClose,
  onTabAdd,
  onTabRename,
}) => {
  const { t } = useTranslation();
  const [editingTabId, setEditingTabId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const inputRef = useRef<InputRef>(null);

  useEffect(() => {
    if (editingTabId && inputRef.current) {
      inputRef.current?.focus();
    }
  }, [editingTabId]);

  const handleDoubleClick = (tabId: string, currentLabel: string) => {
    setEditingTabId(tabId);
    setEditValue(currentLabel);
  };

  const handleEditConfirm = () => {
    if (editingTabId && editValue.trim()) {
      onTabRename(editingTabId, editValue.trim());
    }
    setEditingTabId(null);
    setEditValue('');
  };

  const handleEditCancel = () => {
    setEditingTabId(null);
    setEditValue('');
  };

  const tabItems = tabs.map((tab) => {
    const statusColor = statusConfig[tab.status].color;
    const label = editingTabId === tab.id ? (
      <Input
        ref={inputRef}
        size="small"
        value={editValue}
        onChange={(e) => setEditValue(e.target.value)}
        onPressEnter={handleEditConfirm}
        onBlur={handleEditConfirm}
        onKeyDown={(e) => {
          if (e.key === 'Escape') handleEditCancel();
        }}
        style={{ width: 100, height: 22 }}
        onClick={(e) => e.stopPropagation()}
      />
    ) : (
      <span
        onDoubleClick={(e) => {
          e.stopPropagation();
          handleDoubleClick(tab.id, tab.label);
        }}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
      >
        <span
          style={{
            width: 6,
            height: 6,
            borderRadius: '50%',
            backgroundColor: statusColor,
            display: 'inline-block',
            flexShrink: 0,
          }}
        />
        {tab.label}
      </span>
    );

    return {
      key: tab.id,
      label,
      closable: tabs.length > 1,
    };
  });

  const isMaxReached = tabs.length >= MAX_TABS;

  const onEdit = (
    targetKey: string | React.MouseEvent<Element, MouseEvent> | React.KeyboardEvent<Element>,
    action: 'add' | 'remove',
  ) => {
    if (action === 'add') {
      if (!isMaxReached) onTabAdd();
    } else {
      onTabClose(targetKey as string);
    }
  };

  return (
    <div style={{ marginBottom: 0 }}>
      {isMaxReached ? (
        <Tooltip title={t('terminal.tabs.maxReached', { max: MAX_TABS })}>
          <div>
            <Tabs
              type="editable-card"
              activeKey={activeTabId}
              onChange={onTabChange}
              onEdit={onEdit}
              items={tabItems}
              size="small"
              hideAdd={isMaxReached}
              style={{ marginBottom: 0 }}
            />
          </div>
        </Tooltip>
      ) : (
        <Tabs
          type="editable-card"
          activeKey={activeTabId}
          onChange={onTabChange}
          onEdit={onEdit}
          items={tabItems}
          size="small"
          style={{ marginBottom: 0 }}
        />
      )}
    </div>
  );
};

export default TerminalTabs;
