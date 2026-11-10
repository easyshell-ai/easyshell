import React, { useMemo, useState } from 'react';
import { Card, Empty, Input, Tag, Tooltip } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { HANDLE_CONTRACT } from '../graph';
import { NODE_CATEGORIES, NODE_CATEGORY_LABEL_KEY, NODE_REGISTRY, type NodeCategory } from './nodeRegistry';
import { NodeTypeIcon } from './NodeIcon';
import { CLIPBOARD_MIME } from './canvas/clipboard';
import type { WorkflowNodeType } from '../types';

/**
 * 节点调色板（设计 §3.1）：按 入口 / 执行 / 流程 / 人工 分组 + 搜索。
 *
 * start/end 是新建工作流自动插入的固定节点，不提供拖入；调色板只展示说明。
 * 其余节点支持 HTML5 拖拽与键盘 Enter（分派 wf:palette-drop 给画布，落点同 drop）。
 */

interface PaletteItem {
  type: WorkflowNodeType;
  category: NodeCategory;
  fixed: boolean;
  label: string;
  description: string;
  inputs: number;
  outputs: number;
}

const NodePalette: React.FC = () => {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');

  const items = useMemo<PaletteItem[]>(
    () =>
      NODE_REGISTRY.map((entry) => {
        const handles = HANDLE_CONTRACT[entry.type];
        return {
          type: entry.type,
          category: entry.category,
          fixed: !!entry.fixed,
          label: t(`workflow.node.type.${entry.type}`),
          description: t(`workflow.node.desc.${entry.type}`),
          inputs: handles.inputs,
          outputs: handles.outputs,
        };
      }).filter((item) => {
        if (!keyword.trim()) return true;
        const haystack = `${item.label} ${item.description} ${item.type}`.toLowerCase();
        return haystack.includes(keyword.trim().toLowerCase());
      }),
    [t, keyword]
  );

  const addToCanvas = (type: WorkflowNodeType) => {
    const canvas = document.querySelector('[data-testid="workflow-canvas"] .react-flow');
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    canvas.dispatchEvent(
      new CustomEvent('wf:palette-drop', {
        detail: {
          type,
          clientX: rect.left + rect.width * 0.3,
          clientY: rect.top + rect.height * 0.4,
        },
        bubbles: true,
      })
    );
  };

  return (
    <Card size="small" className="wf-palette" title={<span style={{ fontSize: 13 }}>{t('workflow.palette.title')}</span>}>
      <Input
        allowClear
        size="small"
        prefix={<SearchOutlined />}
        placeholder={t('workflow.palette.search')}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        aria-label={t('workflow.palette.search')}
        style={{ marginBottom: 10 }}
      />
      <div className="wf-palette-scroll">
        {items.length === 0 && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('workflow.palette.noResult')} />
        )}
        {NODE_CATEGORIES.map((category) => {
          const groupItems = items.filter((item) => item.category === category);
          if (groupItems.length === 0) return null;
          return (
            <div key={category} className="wf-palette-group">
              <div className="wf-palette-group-title">{t(NODE_CATEGORY_LABEL_KEY[category])}</div>
              {groupItems.map((item) => (
                <Tooltip key={item.type} title={item.description} placement="right">
                  <div
                    className="wf-palette-item"
                    data-testid={`palette-${item.type}`}
                    data-fixed={item.fixed ? 'true' : 'false'}
                    draggable={!item.fixed}
                    onDragStart={
                      item.fixed
                        ? undefined
                        : (event) => {
                            event.dataTransfer.setData(CLIPBOARD_MIME, JSON.stringify({ type: item.type }));
                            event.dataTransfer.effectAllowed = 'copy';
                          }
                    }
                    role="button"
                    tabIndex={0}
                    aria-disabled={item.fixed || undefined}
                    aria-label={`${item.fixed ? t('workflow.node.fixed') : t('workflow.palette.add')} ${item.label}`}
                    onKeyDown={(event) => {
                      if (event.key !== 'Enter' || item.fixed) return;
                      addToCanvas(item.type);
                    }}
                  >
                    <span className="wf-palette-icon">
                      <NodeTypeIcon type={item.type} />
                    </span>
                    <span className="wf-palette-body">
                      <span className="wf-palette-label">
                        {item.label}
                        {item.fixed && (
                          <Tag className="wf-palette-fixed" color="default">
                            {t('workflow.node.fixed')}
                          </Tag>
                        )}
                      </span>
                      <span className="wf-palette-meta">{item.description}</span>
                      <span className="wf-palette-counts">
                        {t('workflow.palette.handles', { in: item.inputs, out: item.outputs })}
                      </span>
                    </span>
                  </div>
                </Tooltip>
              ))}
            </div>
          );
        })}
      </div>
      <div className="wf-palette-foot">{t('workflow.palette.hint')}</div>
    </Card>
  );
};

export default NodePalette;
