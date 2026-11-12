import type { ReactNode } from 'react';
import { Card, Skeleton } from 'antd';
import { DensityScope } from './density';
import './PageShell.css';

export interface PageShellProps {
  /** 页面标题（H3，20px/500） */
  title: ReactNode;
  /** 标题前的 16px colorPrimary 图标，可省略 */
  icon?: ReactNode;
  /** 标题下方的 12px colorTextTertiary 说明，可省略 */
  description?: ReactNode;
  /** 标题区右侧操作区：≤1 primary + ≤3 secondary（§4.3） */
  extra?: ReactNode;
  /** 筛选区：紧凑密度 + Card bodyPaddingSM（§4.10.3 列表页骨架） */
  filter?: ReactNode;
  /** true 时用骨架屏替换内容区（§4.10.3；完整 4 变体 PageSkeleton 属 W3） */
  loading?: boolean;
  children?: ReactNode;
}

/**
 * 页面骨架模板（设计文档 frontend-redesign-design-v3.md §4.10.3）。
 *
 * ```
 * ┌ 标题区（icon + H3 20px/500） ────── 操作区 ┐
 * ├ 说明 12px colorTextTertiary（可选）        │
 * ├ 筛选区（紧凑密度 Card，可选）              │  间距 16px
 * └ 内容区                                   ┘
 * ```
 *
 * 三类页面（列表 / 工作台 / 详情）共用这一个外壳：差异只在于 filter 与 children 的组成，
 * 外壳本身不持有任何业务状态，也不做数据请求。
 */
const PageShell: React.FC<PageShellProps> = ({
  title,
  icon,
  description,
  extra,
  filter,
  loading = false,
  children,
}) => (
  <div className="es-page">
    <div className="es-page-header">
      <div className="es-page-title">
        {icon && <span className="es-page-title-icon">{icon}</span>}
        <h3 className="es-page-title-text">{title}</h3>
      </div>
      {extra && <div className="es-page-extra">{extra}</div>}
    </div>

    {description && <p className="es-page-description">{description}</p>}

    {filter && (
      <DensityScope compact>
        <Card size="small" className="es-page-filter" styles={{ body: { padding: 12 } }}>
          {filter}
        </Card>
      </DensityScope>
    )}

    <div className="es-page-body">
      {loading ? (
        <Card className="es-page-skeleton">
          <Skeleton active title={{ width: 240 }} paragraph={{ rows: 4 }} />
        </Card>
      ) : (
        children
      )}
    </div>
  </div>
);

export default PageShell;
