import React from 'react';
import { Input, Select, Switch, TreeSelect, Button, Tooltip, theme } from 'antd';
import {
  SendOutlined,
  StopOutlined,
  ToolOutlined,
  ApartmentOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../../hooks/useResponsive';
import { PROVIDER_OPTIONS, MODEL_OPTIONS } from './constants';
import type { TreeNode } from './types';

const { TextArea } = Input;

interface ChatInputProps {
  provider: string;
  model: string;
  enableTools: boolean;
  inputValue: string;
  loading: boolean;
  isStreaming: boolean;
  targetTreeData: TreeNode[];
  selectedTargetIds: string[];
  loadingTargets: boolean;
  inputRef: React.RefObject<any>;
  onProviderChange: (val: string) => void;
  onModelChange: (val: string) => void;
  onEnableToolsChange: (val: boolean) => void;
  onInputChange: (val: string) => void;
  onSelectedTargetIdsChange: (ids: string[]) => void;
  onSend: () => void;
  onStop: () => void;
  onKeyDown: (e: React.KeyboardEvent) => void;
}

const ChatInput: React.FC<ChatInputProps> = ({
  provider, model, enableTools, inputValue, loading, isStreaming,
  targetTreeData, selectedTargetIds, loadingTargets, inputRef,
  onProviderChange, onModelChange, onEnableToolsChange,
  onInputChange, onSelectedTargetIdsChange,
  onSend, onStop, onKeyDown,
}) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [showOptions, setShowOptions] = React.useState(false);

  return (
    <div 
      className="ai-chat-input-wrapper"
      style={{
        padding: isMobile ? '8px 12px 12px' : '12px 24px 16px',
        background: token.colorBgContainer,
        borderTop: `1px solid ${token.colorBorderSecondary}`,
      }}
    >
      {isMobile ? (
        <>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', marginBottom: showOptions ? 8 : 0 }}>
            <Button
              type="text"
              icon={<SettingOutlined />}
              size="small"
              onClick={() => setShowOptions(!showOptions)}
              style={{ color: showOptions ? token.colorPrimary : token.colorTextSecondary }}
            />
            <TextArea
              ref={inputRef}
              value={inputValue}
              onChange={(e) => onInputChange(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder={t('chat.inputPlaceholder')}
              autoSize={{ minRows: 1, maxRows: 4 }}
              style={{ flex: 1, resize: 'none' }}
              disabled={loading}
            />
            {isStreaming ? (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={onStop}
                size="middle"
              />
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={onSend}
                disabled={!inputValue.trim() || loading}
                size="middle"
              />
            )}
          </div>
          {showOptions && (
            <div style={{ 
              display: 'flex', 
              gap: 8, 
              flexWrap: 'wrap',
              padding: '8px 0',
              borderTop: `1px solid ${token.colorBorderSecondary}`,
              marginTop: 8,
            }}>
              <Select
                value={provider}
                onChange={onProviderChange}
                options={PROVIDER_OPTIONS.map(opt => ({
                  ...opt,
                  label: opt.label.startsWith('chat.') ? t(opt.label) : opt.label
                }))}
                style={{ width: 100 }}
                size="small"
              />
              <Select
                value={model}
                onChange={onModelChange}
                options={(MODEL_OPTIONS[provider] || MODEL_OPTIONS.default).map(opt => ({
                  ...opt,
                  label: opt.label.startsWith('chat.') ? t(opt.label) : opt.label
                }))}
                style={{ width: 120 }}
                size="small"
              />
              <TreeSelect
                treeData={targetTreeData}
                value={selectedTargetIds}
                onChange={onSelectedTargetIdsChange}
                treeCheckable
                showCheckedStrategy={TreeSelect.SHOW_CHILD}
                placeholder={t('chat.selectTargets')}
                style={{ flex: 1, minWidth: 120 }}
                size="small"
                maxTagCount={1}
                maxTagTextLength={6}
                allowClear
                loading={loadingTargets}
                treeDefaultExpandAll
                suffixIcon={<ApartmentOutlined />}
              />
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <ToolOutlined style={{ fontSize: 12, color: enableTools ? token.colorPrimary : token.colorTextTertiary }} />
                <Switch size="small" checked={enableTools} onChange={onEnableToolsChange} />
              </div>
            </div>
          )}
        </>
      ) : (
        <>
          <div style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <Select
              value={provider}
              onChange={onProviderChange}
              options={PROVIDER_OPTIONS.map(opt => ({
                ...opt,
                label: opt.label.startsWith('chat.') ? t(opt.label) : opt.label
              }))}
              style={{ width: 110 }}
              size="small"
            />
            <Select
              value={model}
              onChange={onModelChange}
              options={(MODEL_OPTIONS[provider] || MODEL_OPTIONS.default).map(opt => ({
                ...opt,
                label: opt.label.startsWith('chat.') ? t(opt.label) : opt.label
              }))}
              style={{ width: 150 }}
              size="small"
            />
            <TreeSelect
              treeData={targetTreeData}
              value={selectedTargetIds}
              onChange={onSelectedTargetIdsChange}
              treeCheckable
              showCheckedStrategy={TreeSelect.SHOW_CHILD}
              placeholder={t('chat.selectTargets')}
              style={{ minWidth: 200, maxWidth: 300 }}
              size="small"
              maxTagCount={2}
              maxTagTextLength={8}
              allowClear
              loading={loadingTargets}
              treeDefaultExpandAll
              suffixIcon={<ApartmentOutlined />}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 8 }}>
              <Tooltip title={t('chat.toolsTooltip')}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                  <ToolOutlined style={{ fontSize: 13, color: enableTools ? token.colorPrimary : token.colorTextTertiary }} />
                  <span style={{ fontSize: 12, color: token.colorTextSecondary }}>{t('chat.tools')}</span>
                  <Switch size="small" checked={enableTools} onChange={onEnableToolsChange} />
                </div>
              </Tooltip>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <TextArea
              ref={inputRef}
              value={inputValue}
              onChange={(e) => onInputChange(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder={t('chat.inputPlaceholder')}
              autoSize={{ minRows: 1, maxRows: 6 }}
              style={{ flex: 1, resize: 'none' }}
              disabled={loading}
            />
            {isStreaming ? (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={onStop}
                style={{ height: 40 }}
              >
                {t('ai.chat.stop')}
              </Button>
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={onSend}
                disabled={!inputValue.trim() || loading}
                style={{ height: 40 }}
              />
            )}
          </div>
        </>
      )}
    </div>
  );
};

export default ChatInput;
