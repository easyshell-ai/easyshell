import React, { useEffect, useState } from 'react';
import { Button, Input, Space, Tooltip, Typography, Upload, Modal, theme, Progress } from 'antd';
import {
  CloseOutlined,
  UploadOutlined,
  FolderAddOutlined,
  ReloadOutlined,
  ArrowUpOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useFileManager } from '../hooks/useFileManager';
import { FileTree } from './FileTree';

const { Text } = Typography;

interface FileManagerProps {
  agentId: string;
  visible: boolean;
  onClose: () => void;
}

const FileManager: React.FC<FileManagerProps> = ({ agentId, visible, onClose }) => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [pathInput, setPathInput] = useState('/');
  const [newFolderVisible, setNewFolderVisible] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');

  const {
    files,
    currentPath,
    loading,
    uploadProgress,
    uploadPhase,
    downloadProgress,
    navigate,
    upload,
    download,
    mkdir,
    remove,
    rename,
    refresh,
    goUp
  } = useFileManager(agentId);

  useEffect(() => {
    if (visible && agentId) {
      navigate('/');
    }
  }, [visible, agentId, navigate]);

  useEffect(() => {
    setPathInput(currentPath);
  }, [currentPath]);

  const handlePathSubmit = () => {
    if (pathInput && pathInput !== currentPath) {
      navigate(pathInput);
    }
  };

  const handleNewFolder = () => {
    if (newFolderName) {
      mkdir(newFolderName);
      setNewFolderVisible(false);
      setNewFolderName('');
    }
  };

  if (!visible) return null;

  return (
    <div style={{
      width: 360,
      borderLeft: `1px solid ${token.colorBorderSecondary}`,
      background: token.colorBgContainer,
      borderRadius: '0 12px 12px 0',
      display: 'flex',
      flexDirection: 'column',
      height: '100%',
      overflow: 'hidden'
    }}>
      {/* Header */}
      <div style={{
        padding: '12px 16px',
        borderBottom: `1px solid ${token.colorBorderSecondary}`,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <Text strong>{t('terminal.files.title')}</Text>
        <Button type="text" size="small" icon={<CloseOutlined />} onClick={onClose} />
      </div>

      {/* Toolbar & Path */}
      <div style={{ padding: '8px 12px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
        <Space style={{ width: '100%', marginBottom: 8, justifyContent: 'space-between' }}>
          <Space>
            <Tooltip title={t('terminal.files.goUp')}>
              <Button size="small" icon={<ArrowUpOutlined />} onClick={goUp} disabled={currentPath === '/'} />
            </Tooltip>
            <Tooltip title={t('terminal.files.refresh')}>
              <Button size="small" icon={<ReloadOutlined />} onClick={refresh} loading={loading} />
            </Tooltip>
          </Space>
          <Space>
            <Tooltip title={t('terminal.files.newFolder')}>
              <Button size="small" icon={<FolderAddOutlined />} onClick={() => setNewFolderVisible(true)} />
            </Tooltip>
            <Upload
              customRequest={({ file }) => upload(file as File)}
              showUploadList={false}
            >
              <Tooltip title={t('terminal.files.upload')}>
                <Button size="small" icon={<UploadOutlined />} />
              </Tooltip>
            </Upload>
          </Space>
        </Space>
        
        <Input
          size="small"
          value={pathInput}
          onChange={e => setPathInput(e.target.value)}
          onPressEnter={handlePathSubmit}
          placeholder={t('terminal.files.pathPlaceholder')}
        />
      </div>

      {/* Upload Progress */}
      {uploadProgress !== null && (
        <div style={{ padding: '8px 12px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
          <Progress
            percent={uploadProgress}
            size="small"
            status={uploadPhase === 'transferring' ? 'active' : 'active'}
            strokeColor={uploadPhase === 'transferring' ? '#faad14' : undefined}
            format={(percent) =>
              uploadPhase === 'transferring'
                ? t('terminal.files.transferring', 'Transferring to target server...')
                : `${t('terminal.files.upload', 'Upload')} ${percent}%`
            }
          />
        </div>
      )}

      {/* Download Progress */}
      {downloadProgress !== null && (
        <div style={{ padding: '8px 12px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
          <Progress percent={downloadProgress} size="small" status="active" strokeColor="#52c41a" format={(percent) => `${t('terminal.files.download', 'Download')} ${percent}%`} />
        </div>
      )}

      {/* File List */}
      <FileTree
        files={files}
        loading={loading}
        currentPath={currentPath}
        onNavigate={navigate}
        onDownload={download}
        onDelete={remove}
        onRename={rename}
      />

      {/* New Folder Modal */}
      <Modal
        title={t('terminal.files.newFolder')}
        open={newFolderVisible}
        onOk={handleNewFolder}
        onCancel={() => {
          setNewFolderVisible(false);
          setNewFolderName('');
        }}
        width={300}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Input
          autoFocus
          value={newFolderName}
          onChange={e => setNewFolderName(e.target.value)}
          onPressEnter={handleNewFolder}
          placeholder={t('terminal.files.newFolderName')}
        />
      </Modal>
    </div>
  );
};

export default FileManager;
