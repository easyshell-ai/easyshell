/**
 * 布局框架常量与密度两档（设计文档 frontend-redesign-design-v3.md §4.10.4 / §4.10.2）。
 *
 * 三条纪律：
 * 1. 这里的尺寸与 `src/theme/themeConfig.ts` 的 Layout / Menu token、全局间距 token
 *    **同源同值**；CSS 侧由 index.css 读 AntD cssVar 派生出的 `--es-layout-*` /
 *    `--es-menu-*` / `--es-padding-*`，不再手写第二套数值。改这里必须同步改 token，
 *    两边都由 `layoutConstants.test.ts` 机器守着。
 * 2. 密度两档用 **嵌套 ConfigProvider** 覆写（§4.10.2 的"覆盖机制"），不用 CSS 变量：
 *    AntD 6 把组件 token 声明在组件自己的根类上（`:where(.css-hash).es.ant-table`），
 *    祖先选择器改不了它，只有重新注册一份组件 token 才能生效。
 * 3. 本文件只有数字与 token 名，没有任何颜色——颜色唯一来源仍是 src/theme/。
 */

import type { ThemeConfig } from 'antd';

/* ─── 整体框架尺寸（§4.10.4）────────────────────────────────────────── */

/** 侧边栏展开宽度：240 → **216**（挤占表格的元凶） */
export const SIDEBAR_WIDTH = 216;
/** 侧边栏折叠宽度：80 → **56**（与 `Menu.collapsedWidth` token 同值；Sider 的 collapsedWidth 默认是 80，必须显式给） */
export const SIDEBAR_COLLAPSED_WIDTH = 56;
/** 顶栏高度：64 → **52**（与 `Layout.headerHeight` token 同值） */
export const HEADER_HEIGHT = 52;
/** 内容区内边距：24 → **20**（与全局 `paddingContentHorizontalLG` token 同值） */
export const CONTENT_PADDING = 20;
/** 内容区最大宽度：无 → **1600px 居中**（超宽视口里内容居中，两侧留 canvas） */
export const CONTENT_MAX_WIDTH = 1600;
/** 移动端导航抽屉宽度：280 → **240** */
export const MOBILE_DRAWER_WIDTH = 240;
/** 顶栏图标按钮：34×34 → **28×28**（`controlHeightSM`，§4.10.2 白名单必改项） */
export const ICON_BUTTON_SIZE = 28;

/* ─── 页面骨架（§4.10.3）────────────────────────────────────────────── */

/** 标题区与操作区之间的间距 */
export const PAGE_HEADER_GAP = 12;
/** 页面各区块（标题区 / 筛选区 / 内容区）之间的间距 */
export const PAGE_SECTION_GAP = 16;

/* ─── 密度两档（§4.10.2）────────────────────────────────────────────── */

type DensityTheme = Pick<ThemeConfig, 'token' | 'components'>;

/**
 * `default` 列与 §3.8.4 实配逐字一致（它只是把已生效的 token 登记成表，供测试锚定）；
 * `compact` 列是唯一的"新增值"，只经嵌套 ConfigProvider 在紧凑筛选区里生效。
 */
export const DENSITY: Record<'default' | 'compact', DensityTheme> = {
  default: {
    token: {
      controlHeight: 32,
      controlHeightSM: 28,
      fontSize: 14,
      paddingContentHorizontalLG: 20,
    },
    components: {
      Table: { cellPaddingBlock: 8, cellPaddingInline: 12, cellFontSize: 13 },
      Menu: { itemHeight: 34 },
    },
  },
  compact: {
    token: {
      controlHeight: 28,
      controlHeightSM: 24,
      fontSize: 13,
      paddingContentHorizontalLG: 16,
    },
    components: {
      Table: { cellPaddingBlock: 4, cellPaddingInline: 8, cellFontSize: 12 },
      Menu: { itemHeight: 30 },
    },
  },
};

/** CSS 变量名（index.css 里的别名指向这些 AntD 派生变量），集中登记避免散落魔法字符串 */
export const CSS_VAR = {
  headerHeight: '--es-layout-header-height',
  contentPadding: '--es-padding-content-horizontal-lg',
  menuCollapsedWidth: '--es-menu-collapsed-width',
} as const;
