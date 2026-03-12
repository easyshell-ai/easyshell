import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Input, Select, Switch, Space, Typography, message, theme, Spin, Tooltip, Alert } from 'antd';
import {
  ArrowLeftOutlined, SaveOutlined,
  FullscreenOutlined, FullscreenExitOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import CodeMirror from '@uiw/react-codemirror';
import { StreamLanguage } from '@codemirror/language';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { getScript, createScript, updateScript } from '../../api/script';
import ScriptAiPanel from './ScriptAiPanel';

const MIN_PANEL_PCT = 20; // minimum panel width %
const DEFAULT_LEFT_PCT = 55;

export default function ScriptWorkbench() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const isEdit = !!id;

  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);

  // Form State
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [content, setContent] = useState('');
  const [scriptType, setScriptType] = useState('bash');
  const [isPublic, setIsPublic] = useState(false);

  // Panel layout state
  const [leftPct, setLeftPct] = useState(DEFAULT_LEFT_PCT);
  const [expandedPanel, setExpandedPanel] = useState<'left' | 'right' | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const draggingRef = useRef(false);
  const [editorHeight, setEditorHeight] = useState<number | null>(null);
  const editorDraggingRef = useRef(false);
  const editorContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isEdit) {
      loadScript();
    }
  }, [id, isEdit]);

  const loadScript = async () => {
    try {
      setLoading(true);
      const res = await getScript(Number(id));
      if (res.code === 200) {
        const script = res.data;
        setName(script.name);
        setDescription(script.description || '');
        setContent(script.content);
        setScriptType(script.scriptType);
        setIsPublic(script.isPublic);
      } else {
        message.error(res.message || t('script.workbench.loadFailed'));
      }
    } catch (e) {
      message.error(t('script.workbench.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    if (!name.trim()) {
      message.error(t('script.pleaseInputName'));
      return;
    }
    if (!content.trim()) {
      message.error(t('script.pleaseInputContent'));
      return;
    }

    try {
      setSaving(true);
      const payload = {
        name,
        description,
        content,
        scriptType,
        isPublic: isPublic,
      };

      let res;
      if (isEdit) {
        res = await updateScript(Number(id), payload);
      } else {
        res = await createScript(payload);
      }

      if (res.code === 200) {
        message.success(isEdit ? t('common.updateSuccess') : t('common.createSuccess'));
        navigate('/script');
      } else {
        message.error(res.message || t('common.operationFailed'));
      }
    } catch (e) {
      message.error(t('common.operationFailed'));
    } finally {
      setSaving(false);
    }
  };

  const handleAiApply = useCallback((data: { name: string; description: string; content: string }) => {
    setName(data.name || name);
    setDescription(data.description || description);
    setContent(data.content);
  }, []);

  const extensions = useMemo(() => {
    return [StreamLanguage.define(shell)];
  }, []);

  // --- Drag resize handler ---
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    const startX = e.clientX;
    const startPct = leftPct;

    const onMouseMove = (ev: MouseEvent) => {
      if (!draggingRef.current || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const dx = ev.clientX - startX;
      const newPct = startPct + (dx / rect.width) * 100;
      const clamped = Math.min(100 - MIN_PANEL_PCT, Math.max(MIN_PANEL_PCT, newPct));
      setLeftPct(clamped);
    };

    const onMouseUp = () => {
      draggingRef.current = false;
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [leftPct]);

  // --- Expand / Collapse ---
  const toggleExpand = useCallback((panel: 'left' | 'right') => {
    setExpandedPanel(prev => (prev === panel ? null : panel));
  }, []);

  // --- Vertical resize handler for left editor ---
  const handleEditorVResizeDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    editorDraggingRef.current = true;

    // If no explicit height yet, read from the DOM
    const currentH = editorContainerRef.current?.getBoundingClientRect().height || 300;
    const startY = e.clientY;
    const startH = currentH;

    const onMouseMove = (ev: MouseEvent) => {
      if (!editorDraggingRef.current) return;
      const dy = ev.clientY - startY;
      const newH = Math.max(120, startH + dy);
      setEditorHeight(newH);
    };

    const onMouseUp = () => {
      editorDraggingRef.current = false;
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, []);

  // Compute actual widths
  const leftWidth = expandedPanel === 'left' ? '100%' : expandedPanel === 'right' ? '0%' : `${leftPct}%`;
  const rightWidth = expandedPanel === 'right' ? '100%' : expandedPanel === 'left' ? '0%' : `${100 - leftPct}%`;
  const showDivider = !expandedPanel;

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div style={{ 
      display: 'flex', 
      flexDirection: 'column', 
      height: 'var(--content-inner-height, calc(100vh - 112px))',
      backgroundColor: token.colorBgContainer,
      borderRadius: token.borderRadiusLG,
      overflow: 'hidden'
    }}>
      {/* Header */}
      <div style={{ 
        padding: '12px 24px', 
        borderBottom: `1px solid ${token.colorBorderSecondary}`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexShrink: 0,
      }}>
        <Space align="center" size="middle">
          <Button 
            type="text" 
            icon={<ArrowLeftOutlined />} 
            onClick={() => navigate('/script')}
            title={t('script.workbench.backToList')}
          />
          <Typography.Title level={4} style={{ margin: 0 }}>
            {isEdit ? t('script.workbench.editTitle') : t('script.workbench.newTitle')}
            {isEdit && name && ` : ${name}`}
          </Typography.Title>
        </Space>
        <Space>
          <Button onClick={() => navigate('/script')}>
            {t('common.cancel')}
          </Button>
          <Button 
            type="primary" 
            icon={<SaveOutlined />} 
            onClick={handleSave} 
            loading={saving}
          >
            {saving ? t('script.workbench.saving') : t('common.confirm')}
          </Button>
        </Space>
      </div>

      {/* Main Content: Two Columns with resize divider */}
      <div ref={containerRef} style={{ display: 'flex', flex: 1, minHeight: 0, position: 'relative' }}>
        
        {/* Left Column: Form & Editor */}
        <div style={{ 
          width: leftWidth,
          display: expandedPanel === 'right' ? 'none' : 'flex',
          flexDirection: 'column', 
          minHeight: 0,
          overflow: 'hidden',
          transition: expandedPanel !== null ? 'width 0.25s ease' : undefined,
        }}>
          {/* Expand button for left panel */}
          <div style={{ 
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '8px 16px 0 16px', flexShrink: 0,
          }}>
            <Typography.Text strong style={{ fontSize: 13, color: token.colorTextSecondary }}>
              {t('script.workbench.editorPanel')}
            </Typography.Text>
            <Tooltip title={expandedPanel === 'left' ? t('script.workbench.collapse') : t('script.workbench.expand')}>
              <Button
                type="text"
                size="small"
                icon={expandedPanel === 'left' ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
                onClick={() => toggleExpand('left')}
              />
            </Tooltip>
          </div>

          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '8px 16px 16px 16px', minHeight: 0, overflow: 'auto' }}>
            <Space direction="vertical" size="small" style={{ width: '100%', marginBottom: 8, flexShrink: 0 }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                <Input
                  placeholder={t('script.scriptName')}
                  value={name}
                  onChange={e => setName(e.target.value)}
                  style={{ flex: 1 }}
                  size="middle"
                />
                <Select
                  value={scriptType}
                  onChange={setScriptType}
                  style={{ width: 120 }}
                  options={[
                    { value: 'bash', label: 'Bash' },
                    { value: 'shell', label: 'Shell' }
                  ]}
                />
                <Space>
                  <Switch checked={isPublic} onChange={setIsPublic} size="small" />
                  <span style={{ fontSize: 13 }}>{t('script.publicScript')}</span>
                </Space>
              </div>
              <Input
                placeholder={t('script.description')}
                value={description}
                onChange={e => setDescription(e.target.value)}
                size="middle"
              />
            </Space>

            <Alert
              message={t('script.workbench.parameterHint')}
              type="info"
              showIcon
              icon={<InfoCircleOutlined />}
              banner
              style={{ marginBottom: 8, flexShrink: 0 }}
            />

            <div ref={editorContainerRef} style={{ 
              ...(editorHeight ? { height: editorHeight, flexShrink: 0 } : { flex: 1, minHeight: 200 }),
              border: `1px solid ${token.colorBorder}`,
              borderRadius: token.borderRadius,
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}>
              <CodeMirror
                value={content}
                height="100%"
                extensions={extensions}
                onChange={setContent}
                theme={token.colorBgContainer.toLowerCase().includes('000') ? 'dark' : 'light'}
                style={{ flex: 1, overflow: 'auto' }}
              />
            </div>

            {/* Vertical resize handle for editor */}
            <div
              onMouseDown={handleEditorVResizeDown}
              style={{
                height: 6,
                cursor: 'row-resize',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
                marginTop: 2,
              }}
            >
              <div style={{
                width: 40,
                height: 3,
                borderRadius: 2,
                backgroundColor: token.colorBorderSecondary,
              }} />
          </div>
          </div>
        </div>

        {/* Drag Divider */}
        {showDivider && (
          <div
            onMouseDown={handleMouseDown}
            style={{
              width: 5,
              cursor: 'col-resize',
              backgroundColor: 'transparent',
              position: 'relative',
              flexShrink: 0,
              zIndex: 10,
            }}
          >
            {/* Visible line */}
            <div style={{
              position: 'absolute',
              top: 0,
              bottom: 0,
              left: 2,
              width: 1,
              backgroundColor: token.colorBorderSecondary,
            }} />
            {/* Hover indicator dots */}
            <div style={{
              position: 'absolute',
              top: '50%',
              left: '50%',
              transform: 'translate(-50%, -50%)',
              display: 'flex',
              flexDirection: 'column',
              gap: 3,
              opacity: 0.5,
            }}>
              {[0, 1, 2, 3, 4].map(i => (
                <div key={i} style={{
                  width: 3,
                  height: 3,
                  borderRadius: '50%',
                  backgroundColor: token.colorTextQuaternary,
                }} />
              ))}
            </div>
          </div>
        )}

        {/* Right Column: AI Panel */}
        <div style={{ 
          width: rightWidth,
          display: expandedPanel === 'left' ? 'none' : 'flex',
          flexDirection: 'column', 
          minHeight: 0, 
          overflow: 'hidden',
          transition: expandedPanel !== null ? 'width 0.25s ease' : undefined,
        }}>
          {/* Expand button for right panel */}
          <div style={{ 
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '8px 16px 0 16px', flexShrink: 0,
          }}>
            <Typography.Text strong style={{ fontSize: 13, color: token.colorTextSecondary }}>
              {t('script.workbench.aiPanel')}
            </Typography.Text>
            <Tooltip title={expandedPanel === 'right' ? t('script.workbench.collapse') : t('script.workbench.expand')}>
              <Button
                type="text"
                size="small"
                icon={expandedPanel === 'right' ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
                onClick={() => toggleExpand('right')}
              />
            </Tooltip>
          </div>

          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '8px 16px 16px 16px', minHeight: 0, overflow: 'auto' }}>
            <ScriptAiPanel 
              scriptType={scriptType}
              currentScript={content}
              onApply={handleAiApply}
            />
          </div>
        </div>

      </div>
    </div>
  );
}
