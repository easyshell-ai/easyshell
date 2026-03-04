import { useState, useCallback } from 'react';
import { message } from 'antd';
import { useTranslation } from 'react-i18next';
import request from '../../../api/request';

export interface FileInfo {
  name: string;
  isDir: boolean;
  size: number;
  mode: string;
  modTime: number;
}

interface UseFileManagerReturn {
  files: FileInfo[];
  currentPath: string;
  loading: boolean;
  error: string | null;
  uploadProgress: number | null;
  uploadPhase: 'uploading' | 'transferring' | null;
  downloadProgress: number | null;
  navigate: (path: string) => Promise<void>;
  upload: (file: File) => Promise<void>;
  download: (path: string, fileName: string) => Promise<void>;
  mkdir: (name: string) => Promise<void>;
  remove: (path: string) => Promise<void>;
  rename: (oldPath: string, newPath: string) => Promise<void>;
  refresh: () => Promise<void>;
  goUp: () => Promise<void>;
}

export function useFileManager(agentId: string): UseFileManagerReturn {
  const { t } = useTranslation();
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [currentPath, setCurrentPath] = useState('/');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);
  const [uploadPhase, setUploadPhase] = useState<'uploading' | 'transferring' | null>(null);

  const navigate = useCallback(async (path: string) => {
    if (!agentId) return;
    setLoading(true);
    setError(null);
    try {
      const response = await request.get<any, { code: number, data: FileInfo[], message: string }>(
        `/v1/agents/${agentId}/files`,
        { params: { path } }
      );
      if (response.code === 200) {
        setFiles(response.data || []);
        setCurrentPath(path);
      } else {
        throw new Error(response.message || 'Failed to list files');
      }
    } catch (err: any) {
      const errMsg = err.message || 'Unknown error';
      setError(errMsg);
      message.error(t('terminal.files.operationFailed', { error: errMsg }));
    } finally {
      setLoading(false);
    }
  }, [agentId, t]);

  const upload = useCallback(async (file: File) => {
    if (!agentId) return;
    setUploadProgress(0);
    setUploadPhase('uploading');
    try {
      const formData = new FormData();
      formData.append('file', file);
      
      const response = await request.post<any, { code: number, message: string }>(
        `/v1/agents/${agentId}/files/upload`,
        formData,
        {
          params: { path: currentPath },
          headers: { 'Content-Type': 'multipart/form-data' },
          timeout: 0, // No timeout for file uploads
          onUploadProgress: (progressEvent) => {
            if (progressEvent.total) {
              const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
              setUploadProgress(percent);
              if (percent >= 100) {
                setUploadPhase('transferring');
              }
            }
          },
        }
      );
      
      if (response.code === 200) {
        message.success(t('terminal.files.uploadSuccess'));
        await navigate(currentPath);
      } else {
        throw new Error(response.message);
      }
    } catch (err: any) {
      message.error(t('terminal.files.operationFailed', { error: err.message }));
    } finally {
      setUploadProgress(null);
      setUploadPhase(null);
    }
  }, [agentId, currentPath, navigate, t]);

  const download = useCallback(async (path: string, fileName: string) => {
    if (!agentId) return;
    setDownloadProgress(0);
    try {
      const token = localStorage.getItem('token');
      const url = `/api/v1/agents/${agentId}/files/download?path=${encodeURIComponent(path)}`;

      const blob: Blob = await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.responseType = 'blob';
        if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);

        xhr.onprogress = (event) => {
          if (event.lengthComputable && event.total > 0) {
            const percent = Math.round((event.loaded * 100) / event.total);
            setDownloadProgress(percent);
          }
        };

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve(xhr.response as Blob);
          } else {
            reject(new Error(`Download failed with status ${xhr.status}`));
          }
        };

        xhr.onerror = () => reject(new Error('Download failed'));
        xhr.ontimeout = () => reject(new Error('Download timed out'));
        xhr.timeout = 0; // No timeout for file downloads
        xhr.send();
      });

      const blobUrl = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(blobUrl);
      message.success(t('terminal.files.downloadSuccess', 'Download completed'));
    } catch (err: any) {
      message.error(t('terminal.files.operationFailed', { error: err.message }));
    } finally {
      setDownloadProgress(null);
    }
  }, [agentId, t]);

  const mkdir = useCallback(async (name: string) => {
    if (!agentId) return;
    try {
      const fullPath = currentPath.endsWith('/') ? `${currentPath}${name}` : `${currentPath}/${name}`;
      const response = await request.post<any, { code: number, message: string }>(
        `/v1/agents/${agentId}/files/mkdir`,
        null,
        { params: { path: fullPath } }
      );
      
      if (response.code === 200) {
        await navigate(currentPath);
      } else {
        throw new Error(response.message);
      }
    } catch (err: any) {
      message.error(t('terminal.files.operationFailed', { error: err.message }));
    }
  }, [agentId, currentPath, navigate, t]);

  const remove = useCallback(async (path: string) => {
    if (!agentId) return;
    try {
      const response = await request.delete<any, { code: number, message: string }>(
        `/v1/agents/${agentId}/files`,
        { params: { path } }
      );
      
      if (response.code === 200) {
        await navigate(currentPath);
      } else {
        throw new Error(response.message);
      }
    } catch (err: any) {
      message.error(t('terminal.files.operationFailed', { error: err.message }));
    }
  }, [agentId, currentPath, navigate, t]);

  const rename = useCallback(async (oldPath: string, newPath: string) => {
    if (!agentId) return;
    try {
      const response = await request.put<any, { code: number, message: string }>(
        `/v1/agents/${agentId}/files/rename`,
        { oldPath, newPath }
      );
      
      if (response.code === 200) {
        await navigate(currentPath);
      } else {
        throw new Error(response.message);
      }
    } catch (err: any) {
      message.error(t('terminal.files.operationFailed', { error: err.message }));
    }
  }, [agentId, currentPath, navigate, t]);

  const refresh = useCallback(async () => {
    await navigate(currentPath);
  }, [currentPath, navigate]);

  const goUp = useCallback(async () => {
    if (currentPath === '/') return;
    const parent = currentPath.substring(0, currentPath.lastIndexOf('/')) || '/';
    await navigate(parent);
  }, [currentPath, navigate]);

  return {
    files, currentPath, loading, error, uploadProgress, uploadPhase, downloadProgress,
    navigate, upload, download, mkdir, remove, rename, refresh, goUp,
  };
}
