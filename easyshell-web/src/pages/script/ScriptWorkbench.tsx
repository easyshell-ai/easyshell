import { useState, useCallback, useMemo, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Input, Select, Switch, Space, Typography, message, theme, Spin } from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import CodeMirror from '@uiw/react-codemirror';
import { StreamLanguage } from '@codemirror/language';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { python } from '@codemirror/lang-python';
import { getScript, createScript, updateScript } from '../../api/script';
import ScriptAiPanel from './ScriptAiPanel';

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
    return [
      scriptType === 'python' ? python() : StreamLanguage.define(shell)
    ];
  }, [scriptType]);

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
        padding: '16px 24px', 
        borderBottom: `1px solid ${token.colorBorderSecondary}`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between'
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

      {/* Main Content: Two Columns */}
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        
        {/* Left Column: Form & Editor */}
        <div style={{ 
          width: '55%', 
          display: 'flex', 
          flexDirection: 'column', 
          borderRight: `1px solid ${token.colorBorderSecondary}`,
          padding: 24,
          minHeight: 0
        }}>
          <Space direction="vertical" size="middle" style={{ width: '100%', marginBottom: 16 }}>
            <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
              <Input
                placeholder={t('script.scriptName')}
                value={name}
                onChange={e => setName(e.target.value)}
                style={{ flex: 1 }}
              />
              <Select
                value={scriptType}
                onChange={setScriptType}
                style={{ width: 120 }}
                options={[
                  { value: 'bash', label: 'Bash/Shell' },
                  { value: 'python', label: 'Python' }
                ]}
              />
              <Space>
                <Switch checked={isPublic} onChange={setIsPublic} />
                <span>{t('script.publicScript')}</span>
              </Space>
            </div>
            <Input
              placeholder={t('script.description')}
              value={description}
              onChange={e => setDescription(e.target.value)}
            />
          </Space>

          <div style={{ 
            flex: 1, 
            minHeight: 0, 
            border: `1px solid ${token.colorBorder}`,
            borderRadius: token.borderRadius,
            overflow: 'auto',
            display: 'flex',
            flexDirection: 'column'
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
        </div>

        {/* Right Column: AI Panel */}
        <div style={{ width: '45%', display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden' }}>
          <ScriptAiPanel 
            scriptType={scriptType}
            currentScript={content}
            onApply={handleAiApply}
          />
        </div>

      </div>
    </div>
  );
}
