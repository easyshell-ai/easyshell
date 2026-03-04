import { Button, Space, Tooltip } from 'antd';
import {
  SearchOutlined,
  PlusOutlined,
  FullscreenOutlined,
  FullscreenExitOutlined,
  CopyOutlined,
  SnippetsOutlined,
  FolderOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { MAX_TABS } from '../types';

interface TerminalToolbarProps {
  onToggleFiles: () => void;
  onSearch: () => void;
  onAddTab: () => void;
  onToggleFullscreen: () => void;
  onCopy: () => void;
  onPaste: () => void;
  isFullscreen: boolean;
  isFileManagerOpen: boolean;
  tabCount: number;
}

const TerminalToolbar: React.FC<TerminalToolbarProps> = ({
  onToggleFiles,
  onSearch,
  onAddTab,
  onToggleFullscreen,
  onCopy,
  onPaste,
  isFullscreen,
  isFileManagerOpen,
  tabCount,
}) => {
  const { t } = useTranslation();

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 4, gap: 4 }}>
      <Space size={4}>
        <Tooltip title={t('terminal.toolbar.files')}>
          <Button
            type={isFileManagerOpen ? 'default' : 'text'}
            size="small"
            icon={<FolderOutlined />}
            onClick={onToggleFiles}
            disabled={false}
          />
        </Tooltip>
        <Tooltip title={t('terminal.toolbar.search')}>
          <Button
            type="text"
            size="small"
            icon={<SearchOutlined />}
            onClick={onSearch}
          />
        </Tooltip>
        <Tooltip title={t('terminal.toolbar.copy')}>
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            onClick={onCopy}
          />
        </Tooltip>
        <Tooltip title={t('terminal.toolbar.paste')}>
          <Button
            type="text"
            size="small"
            icon={<SnippetsOutlined />}
            onClick={onPaste}
          />
        </Tooltip>
        <Tooltip
          title={
            tabCount >= MAX_TABS
              ? t('terminal.tabs.maxReached', { max: MAX_TABS })
              : t('terminal.toolbar.newTab')
          }
        >
          <Button
            type="text"
            size="small"
            icon={<PlusOutlined />}
            onClick={onAddTab}
            disabled={tabCount >= MAX_TABS}
          />
        </Tooltip>
        <Tooltip
          title={
            isFullscreen
              ? t('terminal.toolbar.exitFullscreen')
              : t('terminal.toolbar.fullscreen')
          }
        >
          <Button
            type="text"
            size="small"
            icon={isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            onClick={onToggleFullscreen}
          />
        </Tooltip>
      </Space>
    </div>
  );
};

export default TerminalToolbar;
