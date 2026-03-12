import { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Select, Input, Space, Typography, message, theme, Tabs, Tag, Tooltip } from 'antd';
import {
  RobotOutlined, ThunderboltOutlined, CheckOutlined, StopOutlined,
  CodeOutlined, DiffOutlined, FileTextOutlined, LoadingOutlined,
  EditOutlined,
} from '@ant-design/icons';
import CodeMirror from '@uiw/react-codemirror';
import CodeMirrorMerge from 'react-codemirror-merge';
import { StreamLanguage } from '@codemirror/language';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { EditorView } from '@codemirror/view';
import { generateScript } from '../../api/script';

const { TextArea } = Input;
const { Text, Paragraph } = Typography;

interface ScriptAiPanelProps {
  scriptType: string;
  currentScript?: string;
  onApply: (data: { name: string; description: string; content: string }) => void;
}

// Section delimiters (must match backend)
const DELIM_NAME = '===SCRIPT_NAME===';
const DELIM_DESC = '===SCRIPT_DESCRIPTION===';
const DELIM_CONTENT = '===SCRIPT_CONTENT===';
const DELIM_SUMMARY = '===CHANGE_SUMMARY===';

const OS_OPTIONS = [
  { value: 'ubuntu', labelKey: 'script.ai.osUbuntu' },
  { value: 'centos', labelKey: 'script.ai.osCentos' },
  { value: 'debian', labelKey: 'script.ai.osDebian' },
  { value: 'rhel', labelKey: 'script.ai.osRhel' },
  { value: 'alpine', labelKey: 'script.ai.osAlpine' },
  { value: 'generic', labelKey: 'script.ai.osGeneric' },
];

/**
 * Parse the raw streamed AI output into structured sections.
 * Works incrementally as content streams in.
 */
function parseSections(raw: string) {
  const sections: { name: string; description: string; content: string; summary: string } = {
    name: '', description: '', content: '', summary: '',
  };

  const nameIdx = raw.indexOf(DELIM_NAME);
  const descIdx = raw.indexOf(DELIM_DESC);
  const contentIdx = raw.indexOf(DELIM_CONTENT);
  const summaryIdx = raw.indexOf(DELIM_SUMMARY);

  if (nameIdx >= 0) {
    const start = nameIdx + DELIM_NAME.length;
    const end = descIdx >= 0 ? descIdx : (contentIdx >= 0 ? contentIdx : raw.length);
    sections.name = raw.substring(start, end).trim();
  }

  if (descIdx >= 0) {
    const start = descIdx + DELIM_DESC.length;
    const end = contentIdx >= 0 ? contentIdx : raw.length;
    sections.description = raw.substring(start, end).trim();
  }

  if (contentIdx >= 0) {
    const start = contentIdx + DELIM_CONTENT.length;
    const end = summaryIdx >= 0 ? summaryIdx : raw.length;
    sections.content = raw.substring(start, end).trim();
  }

  if (summaryIdx >= 0) {
    const start = summaryIdx + DELIM_SUMMARY.length;
    sections.summary = raw.substring(start).trim();
  }

  return sections;
}

/**
 * Detect which section is currently being streamed
 */
function detectActiveSection(raw: string): string | null {
  const summaryIdx = raw.indexOf(DELIM_SUMMARY);
  const contentIdx = raw.indexOf(DELIM_CONTENT);
  const descIdx = raw.indexOf(DELIM_DESC);
  const nameIdx = raw.indexOf(DELIM_NAME);

  if (summaryIdx >= 0) return 'summary';
  if (contentIdx >= 0) return 'content';
  if (descIdx >= 0) return 'description';
  if (nameIdx >= 0) return 'name';
  return null;
}

const ScriptAiPanel: React.FC<ScriptAiPanelProps> = ({ scriptType, currentScript, onApply }) => {
  const { t, i18n } = useTranslation();
  const { token: themeToken } = theme.useToken();
  const [os, setOs] = useState('ubuntu');
  const [prompt, setPrompt] = useState('');
  const [generating, setGenerating] = useState(false);
  const [rawStream, setRawStream] = useState('');
  const [activeTab, setActiveTab] = useState('code');
  const [applied, setApplied] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const originalScriptRef = useRef<string>('');
  const codeEditorViewRef = useRef<EditorView | null>(null);
  const [codeEditorHeight, setCodeEditorHeight] = useState<number | null>(null);
  const codeEditorContainerRef = useRef<HTMLDivElement>(null);
  const codeEditorDraggingRef = useRef(false);

  const isDark = themeToken.colorBgContainer !== '#ffffff' && themeToken.colorBgBase !== '#ffffff';

  // Parse sections from raw stream
  const sections = useMemo(() => parseSections(rawStream), [rawStream]);
  const activeSection = useMemo(() => generating ? detectActiveSection(rawStream) : null, [rawStream, generating]);
  const isModification = !!(currentScript?.trim());

  // CodeMirror extensions based on script type
  const editorExtensions = useMemo(() => {
    const langExt = [StreamLanguage.define(shell)];
    return [...langExt, EditorView.editable.of(false)];
  }, []);

  const editableExtensions = useMemo(() => {
    return [StreamLanguage.define(shell)];
  }, []);

  // Auto-switch to relevant tab as content streams in
  useEffect(() => {
    if (!generating) return;
    if (activeSection === 'content' && activeTab !== 'code') {
      setActiveTab('code');
    } else if (activeSection === 'summary' && activeTab !== 'summary') {
      setActiveTab('summary');
    }
  }, [activeSection, generating, activeTab]);

  // Auto-scroll code editor to bottom during streaming
  useEffect(() => {
    if (generating && activeSection === 'content' && codeEditorViewRef.current) {
      const view = codeEditorViewRef.current;
      const docLength = view.state.doc.length;
      view.dispatch({
        effects: EditorView.scrollIntoView(docLength, { y: 'end' }),
      });
    }
  }, [generating, activeSection, sections.content]);

  // Vertical resize handler for code editor
  const handleCodeEditorVResizeDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    codeEditorDraggingRef.current = true;
    const currentH = codeEditorContainerRef.current?.getBoundingClientRect().height || 300;
    const startY = e.clientY;
    const startH = currentH;

    const onMouseMove = (ev: MouseEvent) => {
      if (!codeEditorDraggingRef.current) return;
      const dy = ev.clientY - startY;
      const newH = Math.max(120, startH + dy);
      setCodeEditorHeight(newH);
    };

    const onMouseUp = () => {
      codeEditorDraggingRef.current = false;
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

  const handleGenerate = useCallback(() => {
    if (!prompt.trim()) return;

    // Save the original script for diff comparison
    originalScriptRef.current = currentScript?.trim() || '';
    setGenerating(true);
    setRawStream('');
    setApplied(false);
    setActiveTab('code');
    let accumulated = '';

    const controller = generateScript(
      {
        prompt: prompt.trim(),
        os,
        scriptType,
        language: i18n.language || 'zh-CN',
        existingScript: currentScript?.trim() || undefined,
      },
      (event) => {
        if (event.type === 'CONTENT' && event.content) {
          accumulated += event.content;
          setRawStream(accumulated);
        } else if (event.type === 'ERROR') {
          message.error(event.content || 'Generation failed');
          setGenerating(false);
        } else if (event.type === 'DONE') {
          setGenerating(false);
        }
        // STEP_START and PLAN_SUMMARY events are parsed from rawStream client-side
        // (they arrive after all CONTENT events, so rawStream already has the full text)
      },
      (error) => {
        message.error(error.message);
        setGenerating(false);
      },
      () => {
        setGenerating(false);
      },
    );

    abortRef.current = controller;
  }, [prompt, os, scriptType, i18n.language, currentScript]);

  const handleStop = useCallback(() => {
    abortRef.current?.abort();
    setGenerating(false);
  }, []);

  const handleApply = useCallback(() => {
    if (!sections.content) {
      message.warning(t('script.ai.noContent'));
      return;
    }

    onApply({
      name: sections.name || '',
      description: sections.description || '',
      content: sections.content,
    });
    setApplied(true);
    message.success(t('script.ai.applySuccess'));
  }, [sections, onApply, t]);

  // Render the Code tab — live streaming CodeMirror with syntax highlighting
  const renderCodeTab = () => {
    const scriptContent = sections.content;
    const isStreaming = generating && activeSection === 'content';

    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 8 }}>
        {/* Metadata header: name + description */}
        {(sections.name || sections.description) && (
          <div style={{
            padding: '8px 12px',
            background: themeToken.colorFillAlter,
            borderRadius: 6,
            border: `1px solid ${themeToken.colorBorderSecondary}`,
            flexShrink: 0,
          }}>
            {sections.name && (
              <div style={{ fontWeight: 600, fontSize: 14, marginBottom: sections.description ? 2 : 0 }}>
                {sections.name}
              </div>
            )}
            {sections.description && (
              <Text type="secondary" style={{ fontSize: 12 }}>{sections.description}</Text>
            )}
          </div>
        )}

        {/* Streaming indicator */}
        {isStreaming && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 6,
            padding: '4px 10px',
            background: themeToken.colorPrimaryBg,
            borderRadius: 4,
            fontSize: 12,
            color: themeToken.colorPrimary,
            flexShrink: 0,
          }}>
            <LoadingOutlined spin />
            <span>{t('script.ai.writingScript')}</span>
          </div>
        )}

        {/* Transfer indicator — shown after streaming but before DONE */}
        {generating && !isStreaming && activeSection !== 'content' && sections.content && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 6,
            padding: '4px 10px',
            background: themeToken.colorWarningBg,
            borderRadius: 4,
            fontSize: 12,
            color: themeToken.colorWarningText,
            flexShrink: 0,
          }}>
            <LoadingOutlined spin />
            <span>{t('script.ai.finalizingOutput')}</span>
          </div>
        )}

        {/* CodeMirror with live syntax highlighting */}
        <div style={{
          flex: 1, minHeight: 200,
          border: `1px solid ${themeToken.colorBorderSecondary}`,
          borderRadius: 8,
          overflow: 'hidden',
        }}>
          {scriptContent ? (
            <CodeMirror
              value={scriptContent}
              extensions={editorExtensions}
              theme={isDark ? 'dark' : 'light'}
              height="100%"
              style={{ height: '100%', overflow: 'auto' }}
              editable={false}
              onCreateEditor={(view) => { codeEditorViewRef.current = view; }}
              basicSetup={{
                lineNumbers: true,
                foldGutter: true,
                highlightActiveLine: false,
                autocompletion: false,
              }}
            />
          ) : (
            <div style={{
              height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: themeToken.colorTextQuaternary,
            }}>
              {generating ? (
                <Space direction="vertical" align="center">
                  <LoadingOutlined style={{ fontSize: 24 }} spin />
                  <Text type="secondary">{t('script.ai.preparingScript')}</Text>
                </Space>
              ) : (
                <Text type="secondary" italic>{t('script.ai.noContent')}</Text>
              )}
            </div>
          )}
        </div>
      </div>
    );
  };

  // Render the Diff tab — side-by-side comparison using CodeMirrorMerge
  const renderDiffTab = () => {
    const original = originalScriptRef.current;
    const modified = sections.content || '';

    if (!original && !modified) {
      return (
        <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text type="secondary" italic>{t('script.ai.noDiffContent')}</Text>
        </div>
      );
    }

    if (!original) {
      return (
        <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Space direction="vertical" align="center">
            <Tag color="green">{t('script.ai.newScript')}</Tag>
            <Text type="secondary">{t('script.ai.noDiffNewScript')}</Text>
          </Space>
        </div>
      );
    }

    return (
      <div style={{
        height: '100%',
        border: `1px solid ${themeToken.colorBorderSecondary}`,
        borderRadius: 8,
        overflow: 'auto',
      }}>
        <CodeMirrorMerge
          theme={isDark ? 'dark' : 'light'}
          style={{ width: '100%' }}
        >
          <CodeMirrorMerge.Original
            value={original}
            extensions={editableExtensions}
          />
          <CodeMirrorMerge.Modified
            value={modified}
            extensions={editableExtensions}
          />
        </CodeMirrorMerge>
      </div>
    );
  };

  // Render the Summary tab — AI's description of changes
  const renderSummaryTab = () => {
    const summary = sections.summary;
    const isSummaryStreaming = generating && activeSection === 'summary';

    return (
      <div style={{ height: '100%', overflow: 'auto', padding: '8px 4px' }}>
        {summary ? (
          <div>
            {isSummaryStreaming && (
              <div style={{
                display: 'flex', alignItems: 'center', gap: 6,
                marginBottom: 8, padding: '4px 10px',
                background: themeToken.colorPrimaryBg,
                borderRadius: 4, fontSize: 12, color: themeToken.colorPrimary,
              }}>
                <LoadingOutlined spin />
                <span>{t('script.ai.writingSummary')}</span>
              </div>
            )}
            <div style={{
              padding: 12,
              background: themeToken.colorFillAlter,
              borderRadius: 8,
              border: `1px solid ${themeToken.colorBorderSecondary}`,
            }}>
              <Paragraph style={{
                margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: 13,
              }}>
                {summary}
              </Paragraph>
            </div>
          </div>
        ) : (
          <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {generating ? (
              <Space direction="vertical" align="center">
                <LoadingOutlined style={{ fontSize: 24 }} spin />
                <Text type="secondary">{t('script.ai.waitingSummary')}</Text>
              </Space>
            ) : isModification ? (
              <Text type="secondary" italic>{t('script.ai.noSummary')}</Text>
            ) : (
              <Text type="secondary" italic>{t('script.ai.summaryOnlyModify')}</Text>
            )}
          </div>
        )}
      </div>
    );
  };

  // Build tab items
  const tabItems = useMemo(() => {
    const items = [
      {
        key: 'code',
        label: (
          <Space size={4}>
            <CodeOutlined />
            <span>{t('script.ai.tabCode')}</span>
            {generating && activeSection === 'content' && (
              <LoadingOutlined spin style={{ fontSize: 12, color: themeToken.colorPrimary }} />
            )}
          </Space>
        ),
        children: renderCodeTab(),
      },
    ];

    // Only show diff tab when there's an original script (modification mode)
    if (isModification || originalScriptRef.current) {
      items.push({
        key: 'diff',
        label: (
          <Space size={4}>
            <DiffOutlined />
            <span>{t('script.ai.tabDiff')}</span>
          </Space>
        ),
        children: renderDiffTab(),
      });
    }

    // Show summary tab for modifications
    if (isModification || sections.summary) {
      items.push({
        key: 'summary',
        label: (
          <Space size={4}>
            <FileTextOutlined />
            <span>{t('script.ai.tabSummary')}</span>
            {generating && activeSection === 'summary' && (
              <LoadingOutlined spin style={{ fontSize: 12, color: themeToken.colorPrimary }} />
            )}
          </Space>
        ),
        children: renderSummaryTab(),
      });
    }

    return items;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rawStream, generating, activeSection, activeTab, sections, isModification, isDark, themeToken, t, scriptType]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 10 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Space size={8}>
          <RobotOutlined style={{ fontSize: 18, color: themeToken.colorPrimary }} />
          <Text strong style={{ fontSize: 15 }}>{t('script.ai.title')}</Text>
        </Space>
        {isModification && (
          <Tooltip title={t('script.ai.modifyMode')}>
            <Tag icon={<EditOutlined />} color="blue">{t('script.ai.modifying')}</Tag>
          </Tooltip>
        )}
      </div>

      {/* OS Selector */}
      <div>
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
          {t('script.ai.targetOs')}
        </Text>
        <Select
          value={os}
          onChange={setOs}
          style={{ width: '100%' }}
          options={OS_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey) }))}
        />
      </div>

      {/* Prompt Input */}
      <div>
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
          {t('script.ai.promptLabel')}
        </Text>
        <TextArea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder={isModification ? t('script.ai.modifyPlaceholder') : t('script.ai.promptPlaceholder')}
          autoSize={{ minRows: 2, maxRows: 5 }}
          disabled={generating}
          onPressEnter={(e) => {
            if (e.ctrlKey || e.metaKey) {
              e.preventDefault();
              handleGenerate();
            }
          }}
        />
      </div>

      {/* Generate / Stop Button */}
      <Space>
        {generating ? (
          <Button icon={<StopOutlined />} onClick={handleStop} danger>
            {t('script.ai.stop')}
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            onClick={handleGenerate}
            disabled={!prompt.trim()}
          >
            {t('script.ai.generate')}
          </Button>
        )}
      </Space>

      {/* Tabbed Content Area */}
      <div ref={codeEditorContainerRef} style={{
        ...(codeEditorHeight ? { height: codeEditorHeight, flexShrink: 0 } : { flex: 1, minHeight: 0 }),
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          size="small"
          className="script-ai-tabs"
          style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}
          items={tabItems}
          tabBarStyle={{ marginBottom: 8, flexShrink: 0 }}
        />
      </div>

      {/* Vertical resize handle for AI panel */}
      <div
        onMouseDown={handleCodeEditorVResizeDown}
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
          backgroundColor: themeToken.colorBorderSecondary,
        }} />
      </div>

      {/* Apply Button */}
      {sections.content && !generating && (
        <Button
          type="primary"
          icon={applied ? <CheckOutlined /> : <CheckOutlined />}
          onClick={handleApply}
          block
          style={applied
            ? { background: themeToken.colorSuccess, borderColor: themeToken.colorSuccess }
            : {}
          }
        >
          {applied ? t('script.ai.applied') : t('script.ai.apply')}
        </Button>
      )}

      {/* Cursor blink animation */}
      <style>{`
        @keyframes blink {
          0%, 100% { opacity: 1; }
          50% { opacity: 0; }
        }
        .cm-merge-a .cm-changedLine { background: rgba(255, 100, 100, 0.15) !important; }
        .cm-merge-b .cm-changedLine { background: rgba(100, 255, 100, 0.15) !important; }
        /* Fix Ant Design Tabs to fill available height */
        .script-ai-tabs .ant-tabs-content { height: 100%; }
        .script-ai-tabs .ant-tabs-content > .ant-tabs-tabpane { height: 100%; }
        .script-ai-tabs .ant-tabs-content > .ant-tabs-tabpane-active { display: flex; flex-direction: column; }
        /* Constrain CodeMirrorMerge width */
        .script-ai-tabs .cm-mergeView { max-width: 100%; overflow-x: auto; }
        .script-ai-tabs .cm-editor { max-height: 100%; }
      `}</style>
    </div>
  );
};

export default ScriptAiPanel;
