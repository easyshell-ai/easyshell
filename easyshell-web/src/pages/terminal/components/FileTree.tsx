import React, { useState } from 'react';
import { List, Button, Space, Typography, Tooltip, Input, Modal } from 'antd';
import {
  FolderOutlined,
  FileOutlined,
  DownloadOutlined,
  DeleteOutlined,
  EditOutlined,
  CheckOutlined,
  CloseOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { FileInfo } from '../hooks/useFileManager';

const { Text } = Typography;

interface FileTreeProps {
  files: FileInfo[];
  loading: boolean;
  currentPath: string;
  onNavigate: (path: string) => void;
  onDownload: (path: string, fileName: string) => void;
  onDelete: (path: string) => void;
  onRename: (oldPath: string, newPath: string) => void;
}

export const FileTree: React.FC<FileTreeProps> = ({
  files,
  loading,
  currentPath,
  onNavigate,
  onDownload,
  onDelete,
  onRename,
}) => {
  const { t } = useTranslation();
  const [editingFile, setEditingFile] = useState<string | null>(null);
  const [editName, setEditName] = useState('');

  const formatSize = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const getFullPath = (fileName: string) => {
    return currentPath.endsWith('/') ? `${currentPath}${fileName}` : `${currentPath}/${fileName}`;
  };

  const sortedFiles = [...files].sort((a, b) => {
    if (a.isDir && !b.isDir) return -1;
    if (!a.isDir && b.isDir) return 1;
    return a.name.localeCompare(b.name);
  });

  const handleDelete = (file: FileInfo) => {
    Modal.confirm({
      title: t('terminal.files.delete'),
      content: t('terminal.files.deleteConfirm', { name: file.name }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      okButtonProps: { danger: true },
      onOk: () => onDelete(getFullPath(file.name)),
    });
  };

  const startEdit = (file: FileInfo) => {
    setEditingFile(file.name);
    setEditName(file.name);
  };

  const submitEdit = (oldName: string) => {
    if (editName && editName !== oldName) {
      const oldPath = getFullPath(oldName);
      const newPath = getFullPath(editName);
      onRename(oldPath, newPath);
    }
    setEditingFile(null);
  };

  const cancelEdit = () => {
    setEditingFile(null);
    setEditName('');
  };

  return (
    <List
      loading={loading}
      dataSource={sortedFiles}
      size="small"
      style={{ overflow: 'auto', flex: 1 }}
      renderItem={(item) => {
        const isEditing = editingFile === item.name;

        return (
          <List.Item
            key={item.name}
            style={{ padding: '8px 12px' }}
            actions={
              isEditing ? [
                <Button key="save" type="text" size="small" icon={<CheckOutlined />} onClick={() => submitEdit(item.name)} style={{ color: '#52c41a' }} />,
                <Button key="cancel" type="text" size="small" icon={<CloseOutlined />} onClick={cancelEdit} />
              ] : [
                !item.isDir && (
                  <Tooltip key="download" title={t('terminal.files.download')}>
                    <Button type="text" size="small" icon={<DownloadOutlined />} onClick={() => onDownload(getFullPath(item.name), item.name)} />
                  </Tooltip>
                ),
                <Tooltip key="rename" title={t('terminal.files.rename')}>
                  <Button type="text" size="small" icon={<EditOutlined />} onClick={() => startEdit(item)} />
                </Tooltip>,
                <Tooltip key="delete" title={t('terminal.files.delete')}>
                  <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleDelete(item)} />
                </Tooltip>
              ].filter(Boolean) as React.ReactNode[]
            }
          >
            <List.Item.Meta
              avatar={item.isDir ? <FolderOutlined style={{ color: '#1890ff', fontSize: 18 }} /> : <FileOutlined style={{ fontSize: 18, color: '#8c8c8c' }} />}
              title={
                isEditing ? (
                  <Input
                    size="small"
                    value={editName}
                    onChange={e => setEditName(e.target.value)}
                    onPressEnter={() => submitEdit(item.name)}
                    onBlur={cancelEdit}
                    autoFocus
                  />
                ) : (
                  <Text
                    style={{ cursor: item.isDir ? 'pointer' : 'default', color: item.isDir ? '#1890ff' : 'inherit' }}
                    onClick={() => item.isDir && onNavigate(getFullPath(item.name))}
                    ellipsis={{ tooltip: item.name }}
                  >
                    {item.name}
                  </Text>
                )
              }
              description={
                <Space size="small" style={{ fontSize: 12, color: '#8c8c8c' }}>
                  <span>{!item.isDir ? formatSize(item.size) : '--'}</span>
                  <span>{new Date(item.modTime * 1000).toLocaleString()}</span>
                  <span>{item.mode}</span>
                </Space>
              }
              style={{ margin: 0 }}
            />
          </List.Item>
        );
      }}
    />
  );
};
