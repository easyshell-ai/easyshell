# EasyShell Web Terminal 增强 — 技术规格说明书（Tech Spec）

> 文档版本：v1.0  
> 创建日期：2026-03-04  
> 关联 PRD：[PRD-WebTerminal-Enhancement.md](./PRD-WebTerminal-Enhancement.md)  
> 状态：Draft

---

## 目录

1. [概述](#1-概述)
2. [系统架构](#2-系统架构)
3. [Phase 1：终端 UX 增强（前端）](#3-phase-1终端-ux-增强前端)
4. [Phase 2：文件管理（全栈）](#4-phase-2文件管理全栈)
5. [安全规格](#5-安全规格)
6. [数据库变更](#6-数据库变更)
7. [配置变更](#7-配置变更)
8. [API 规格](#8-api-规格)
9. [错误处理](#9-错误处理)
10. [测试计划](#10-测试计划)
11. [部署与兼容性](#11-部署与兼容性)

---

## 1. 概述

### 1.1 范围

本 spec 覆盖 PRD Phase 1 + Phase 2，即：

| Phase | 功能 | 涉及层 |
|-------|------|--------|
| Phase 1 | 多标签页、终端内搜索、工具栏、全屏 | 前端 |
| Phase 2 | SFTP 文件管理（列目录、上传、下载、删除、创建、重命名） | Agent + Server + 前端 |

Phase 3/4（主题、断线重连、分屏、录制等）不在本 spec 范围内。

### 1.2 现有代码结构

```
easyshell-server/   (Java 17, Spring Boot 3.5, Gradle)
├── com.easyshell.server.common.result.R          # 统一响应包装
├── com.easyshell.server.common.exception.BusinessException
├── com.easyshell.server.config.SecurityConfig     # Spring Security (JWT + Stateless)
├── com.easyshell.server.config.WebSocketConfig    # WS 端点注册
├── com.easyshell.server.websocket.AgentWebSocketHandler  # Agent WS 管理
├── com.easyshell.server.websocket.TerminalWebSocketHandler
├── com.easyshell.server.model.entity.AuditLog     # 审计日志实体
├── com.easyshell.server.service.AuditLogService   # 审计接口 (async)
├── com.easyshell.server.controller.HostController  # REST 参考
└── ...

easyshell-agent/   (Go 1.24, 单二进制)
├── cmd/agent/main.go                              # 入口：register → heartbeat → ws
├── internal/client/http.go                        # HTTPClient（Server 通信）
├── internal/ws/client.go                          # WebSocket 客户端
├── internal/terminal/terminal.go                  # PTY 会话管理
├── internal/config/config.go                      # YAML 配置
└── internal/executor/...                          # 脚本执行器

easyshell-web/   (React 19, TypeScript, Vite 7, Ant Design 6)
├── src/pages/terminal/index.tsx                   # 终端页面（单会话）
├── src/hooks/useWebSocket.ts                      # WS Hook
└── src/router/index.tsx                           # 路由 /terminal/:agentId
```

### 1.3 关键约束

- **Hibernate `ddl-auto: update`**：新增字段/表自动生成，无需手写 migration SQL
- **Agent 零外部依赖**：不引入第三方 Go 库（`creack/pty`、`gorilla/websocket`、`gopkg.in/yaml.v3` 已有）
- **Agent ↔ Server 通信**：已有两条通道 — HTTP（注册/心跳/结果上报）和 WebSocket（实时消息）
- **安全模型**：Browser→Server 走 JWT（`JwtAuthenticationFilter`），Agent→Server 的 `/api/v1/agent/**` 路径当前 `permitAll()`
- **统一响应**：所有 REST API 返回 `R<T>`（`{code, message, data}`）

---

## 2. 系统架构

### 2.1 Phase 1 架构（无后端改动）

```
Browser (xterm.js + addons)
  ├── TerminalTabs       ← 新增：管理多个 TerminalInstance
  ├── TerminalInstance   ← 重构：从 index.tsx 提取，每 tab 一个
  ├── TerminalToolbar    ← 新增：工具栏
  └── SearchBar          ← 新增：@xterm/addon-search
        │
        │  WebSocket (现有协议，无改动)
        ▼
Server (TerminalWebSocketHandler) ─── 无改动
        │
        ▼
Agent (terminal.Manager) ─── 无改动
```

### 2.2 Phase 2 架构（文件管理）

```
Browser
  ├── FileManager        ← 新增：侧边栏文件管理面板
  │     │
  │     │  REST API (新增)
  │     ▼
  │   Server (FileProxyController)  ← 新增：代理转发
  │     │
  │     │  WebSocket 消息 (新增消息类型)
  │     ▼
  │   Agent (fileserver)  ← 新增：文件操作处理
  │
  └── TerminalInstance   ← 现有，无改动
        │
        │  WebSocket (现有协议)
        ▼
      Server → Agent     ← 现有通道
```

**文件操作选择 WebSocket 通道而非 Agent 独立 HTTP Server 的理由：**

| 方案 | 优点 | 缺点 |
|------|------|------|
| Agent 开 HTTP Server | 解耦、支持 Range | Agent 需暴露端口、防火墙问题、额外鉴权 |
| **复用现有 WebSocket 通道** | **零网络变更、已有鉴权通道、防火墙友好** | 大文件需分块、不支持 Range |

**最终方案：复用 Server↔Agent 的 WebSocket 通道**，通过新增消息类型传输文件元数据和分块数据。Server 端通过 REST API 接收浏览器请求，转换为 WebSocket 消息发给 Agent，Agent 处理后通过 WebSocket 返回结果。Server 端做流式组装后返回给浏览器。

**大文件上传**：Server 接收 multipart，分块（每块 256KB）通过 WS 发送；Agent 端写入临时文件，完成后原子 rename。

**大文件下载**：Agent 分块（每块 256KB）通过 WS 发送；Server 端流式写入 HTTP Response（`StreamingResponseBody`）。

---

## 3. Phase 1：终端 UX 增强（前端）

### 3.1 文件变更清单

| 文件 | 操作 | 描述 |
|------|------|------|
| `src/pages/terminal/index.tsx` | 重构 | 提取为 TabManager + Layout |
| `src/pages/terminal/components/TerminalInstance.tsx` | 新增 | 单终端实例（从 index.tsx 提取） |
| `src/pages/terminal/components/TerminalTabs.tsx` | 新增 | 标签栏管理 |
| `src/pages/terminal/components/TerminalToolbar.tsx` | 新增 | 工具栏 |
| `src/pages/terminal/components/SearchBar.tsx` | 新增 | 终端搜索浮层 |
| `src/pages/terminal/hooks/useTerminal.ts` | 新增 | xterm 实例生命周期 Hook |
| `src/pages/terminal/types.ts` | 新增 | 类型定义 |
| `src/i18n/locales/en-US.json` | 修改 | 新增 terminal.* 键 |
| `src/i18n/locales/zh-CN.json` | 修改 | 新增 terminal.* 键 |
| `package.json` | 修改 | 新增 `@xterm/addon-search` 依赖 |

### 3.2 types.ts

```typescript
export interface TerminalTab {
  id: string;                    // uuid
  label: string;                 // 显示名（默认 "Terminal 1"）
  agentId: string;               // 当前连接的 agentId
  status: 'connecting' | 'connected' | 'disconnected';
}

export interface TerminalSettings {
  fontSize: number;              // 12-20, 默认 14
  fontFamily: string;            // 默认 JetBrains Mono
  theme: 'dark' | 'dracula' | 'solarized' | 'monokai' | 'light';
  scrollback: number;            // 默认 5000
}
```

### 3.3 TerminalInstance.tsx

从现有 `index.tsx` 提取，接收 Props：

```typescript
interface TerminalInstanceProps {
  tabId: string;
  agentId: string;
  isActive: boolean;             // 是否当前可见 tab
  onStatusChange: (tabId: string, status: ConnectionStatus) => void;
}
```

**核心逻辑**（保留现有行为）：
- `useEffect` 创建 `Terminal` + `FitAddon` + `WebLinksAddon` + **`SearchAddon`**（新增）
- WebSocket 连接到 `/ws/terminal/${agentId}`
- `isActive` 控制 `display: none` 切换（不销毁 DOM，保留会话）
- 暴露 `ref` 方法：`searchOpen()`, `searchNext()`, `searchPrev()`, `copySelection()`, `pasteFromClipboard()`

**关键实现细节**：
- 非活跃 tab 设 `display: none`，不用 `v-if` 式销毁，避免丢失 WebSocket 连接和 terminal buffer
- 切换到活跃时调用 `fitAddon.fit()` 重新适配尺寸

### 3.4 TerminalTabs.tsx

```typescript
interface TerminalTabsProps {
  tabs: TerminalTab[];
  activeTabId: string;
  onTabChange: (tabId: string) => void;
  onTabClose: (tabId: string) => void;
  onTabAdd: () => void;
  onTabRename: (tabId: string, newLabel: string) => void;
}
```

**实现**：
- 使用 Ant Design `Tabs` 组件，`type="editable-card"`
- 标签名双击进入编辑模式（`Input` 替换 `span`）
- 最大 8 个标签，达上限后禁用「+」按钮并 tooltip 提示
- 标签显示连接状态色点（复用现有 statusConfig）

### 3.5 TerminalToolbar.tsx

```typescript
interface TerminalToolbarProps {
  onToggleFiles: () => void;      // Phase 2
  onSearch: () => void;
  onAddTab: () => void;
  onToggleFullscreen: () => void;
  onCopy: () => void;
  onPaste: () => void;
  isFullscreen: boolean;
  isFileManagerOpen: boolean;     // Phase 2
  tabCount: number;
  maxTabs: number;
}
```

**实现**：
- 横排 `Space` 包裹 `Button`（`size="small"`, `type="text"`），带 `Tooltip`
- 文件按钮 Phase 1 中 `disabled`，Phase 2 启用
- 全屏使用 `document.documentElement.requestFullscreen()` / `document.exitFullscreen()`

### 3.6 SearchBar.tsx

```typescript
interface SearchBarProps {
  searchAddon: SearchAddon | null;
  visible: boolean;
  onClose: () => void;
}
```

**实现**：
- 浮层定位在终端右上角（`position: absolute`）
- `Input` + 上/下箭头按钮 + 关闭按钮
- `Checkbox` 切换大小写敏感 / 正则模式
- `searchAddon.findNext(term, options)` / `searchAddon.findPrevious(term, options)`
- `Escape` 键关闭
- 快捷键绑定：`Ctrl+Shift+F` 打开/聚焦

### 3.7 index.tsx（重构后）

```typescript
const TerminalPage: React.FC = () => {
  const { agentId } = useParams<{ agentId: string }>();
  const [tabs, setTabs] = useState<TerminalTab[]>([初始 tab]);
  const [activeTabId, setActiveTabId] = useState<string>(初始 id);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isFileManagerOpen, setIsFileManagerOpen] = useState(false);
  const [searchVisible, setSearchVisible] = useState(false);

  // 多 tab 管理逻辑
  const handleAddTab = () => { /* 新建 tab, 复用当前 agentId */ };
  const handleCloseTab = (tabId: string) => { /* 关闭 WS, 移除 tab */ };
  const handleRenameTab = (tabId: string, label: string) => { /* 更新 label */ };

  return (
    <div style={{ height: 'var(--content-inner-height)', display: 'flex', flexDirection: 'column' }}>
      {/* 返回按钮 + 标题 + 连接状态 (保留现有 header) */}
      <Header ... />
      
      {/* 工具栏 */}
      <TerminalToolbar ... />
      
      {/* 标签栏 */}
      <TerminalTabs ... />
      
      {/* 终端区域 + 文件管理面板 (Phase 2) */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <div style={{ flex: 1, position: 'relative' }}>
          {tabs.map(tab => (
            <TerminalInstance
              key={tab.id}
              tabId={tab.id}
              agentId={tab.agentId}
              isActive={tab.id === activeTabId}
              onStatusChange={handleStatusChange}
            />
          ))}
          {/* 搜索浮层 */}
          <SearchBar visible={searchVisible} ... />
        </div>
        
        {/* Phase 2: FileManager 侧边栏 */}
        {isFileManagerOpen && <FileManager agentId={agentId} />}
      </div>
    </div>
  );
};
```

### 3.8 快捷键映射

| 快捷键 | 行为 |
|--------|------|
| `Ctrl+Shift+F` | 打开/聚焦搜索框 |
| `Escape` | 关闭搜索框 |
| `Enter`（搜索框内） | 查找下一个 |
| `Shift+Enter`（搜索框内） | 查找上一个 |
| `F11` 或全屏按钮 | 切换全屏 |

### 3.9 i18n 新增键

```json
{
  "terminal.toolbar.files": "Files",
  "terminal.toolbar.search": "Search",
  "terminal.toolbar.newTab": "New Tab",
  "terminal.toolbar.fullscreen": "Fullscreen",
  "terminal.toolbar.exitFullscreen": "Exit Fullscreen",
  "terminal.toolbar.settings": "Settings",
  "terminal.toolbar.copy": "Copy",
  "terminal.toolbar.paste": "Paste",
  "terminal.tabs.maxReached": "Maximum {{max}} tabs allowed",
  "terminal.tabs.defaultName": "Terminal {{index}}",
  "terminal.search.placeholder": "Search...",
  "terminal.search.caseSensitive": "Case Sensitive",
  "terminal.search.regex": "Regex",
  "terminal.search.noResults": "No results found"
}
```

---

## 4. Phase 2：文件管理（全栈）

### 4.1 Agent 端

#### 4.1.1 新增文件

| 文件路径 | 描述 |
|----------|------|
| `internal/fileserver/handler.go` | 文件操作核心逻辑 |
| `internal/fileserver/security.go` | 路径校验 + 黑名单 + 权限 |
| `internal/fileserver/types.go` | 消息类型定义 |

#### 4.1.2 types.go — 消息协议

```go
package fileserver

// === 请求消息（Server → Agent，通过 WebSocket）===

// FileListRequest 列出目录
type FileListRequest struct {
    Type      string `json:"type"`      // "file_list"
    RequestID string `json:"requestId"` // Server 生成，用于关联响应
    Path      string `json:"path"`      // 目标目录路径
}

// FileDownloadRequest 下载文件
type FileDownloadRequest struct {
    Type      string `json:"type"`      // "file_download"
    RequestID string `json:"requestId"`
    Path      string `json:"path"`
}

// FileUploadStartRequest 开始上传
type FileUploadStartRequest struct {
    Type      string `json:"type"`      // "file_upload_start"
    RequestID string `json:"requestId"`
    Path      string `json:"path"`      // 目标文件完整路径
    Size      int64  `json:"size"`      // 文件总大小（字节）
}

// FileUploadChunkRequest 上传分块
type FileUploadChunkRequest struct {
    Type      string `json:"type"`      // "file_upload_chunk"
    RequestID string `json:"requestId"`
    Index     int    `json:"index"`     // 分块序号（0-based）
    Data      string `json:"data"`      // Base64 编码的分块数据
    Final     bool   `json:"final"`     // 是否最后一块
}

// FileMkdirRequest 创建目录
type FileMkdirRequest struct {
    Type      string `json:"type"`      // "file_mkdir"
    RequestID string `json:"requestId"`
    Path      string `json:"path"`
}

// FileDeleteRequest 删除文件/目录
type FileDeleteRequest struct {
    Type      string `json:"type"`      // "file_delete"
    RequestID string `json:"requestId"`
    Path      string `json:"path"`
}

// FileRenameRequest 重命名
type FileRenameRequest struct {
    Type      string `json:"type"`      // "file_rename"
    RequestID string `json:"requestId"`
    OldPath   string `json:"oldPath"`
    NewPath   string `json:"newPath"`
}

// === 响应消息（Agent → Server，通过 WebSocket）===

// FileListResponse 目录列表响应
type FileListResponse struct {
    Type      string     `json:"type"`      // "file_list_result"
    RequestID string     `json:"requestId"`
    Success   bool       `json:"success"`
    Error     string     `json:"error,omitempty"`
    Path      string     `json:"path"`
    Entries   []FileInfo `json:"entries,omitempty"`
}

type FileInfo struct {
    Name    string `json:"name"`
    IsDir   bool   `json:"isDir"`
    Size    int64  `json:"size"`
    Mode    string `json:"mode"`    // "drwxr-xr-x"
    ModTime int64  `json:"modTime"` // Unix 时间戳（毫秒）
}

// FileDownloadChunk 下载分块响应
type FileDownloadChunk struct {
    Type      string `json:"type"`      // "file_download_chunk"
    RequestID string `json:"requestId"`
    Index     int    `json:"index"`
    Data      string `json:"data"`      // Base64
    Final     bool   `json:"final"`
    TotalSize int64  `json:"totalSize"` // 仅首块携带
}

// FileOperationResult 通用操作结果
type FileOperationResult struct {
    Type      string `json:"type"`      // "file_result"
    RequestID string `json:"requestId"`
    Success   bool   `json:"success"`
    Error     string `json:"error,omitempty"`
}
```

#### 4.1.3 security.go — 安全核心

```go
package fileserver

import (
    "errors"
    "fmt"
    "os"
    "path/filepath"
    "regexp"
    "strings"
)

var (
    ErrPathTraversal    = errors.New("path traversal detected")
    ErrAccessDenied     = errors.New("access denied: path matches blacklist")
    ErrSymlinkTraversal = errors.New("access denied: symlink points outside root")
    ErrReadOnly         = errors.New("write operation denied: read-only mode")
)

// SecurityConfig 文件操作安全配置
type SecurityConfig struct {
    Enabled         bool     // 是否启用文件操作
    RootPath        string   // 文件操作根目录
    ReadOnly        bool     // 只读模式
    MaxFileSize     int64    // 单文件最大字节数
    MaxListEntries  int      // 目录列表最大条目数
    BlacklistRegexp []*regexp.Regexp  // 编译后的黑名单正则
    WhitelistPaths  []string // 白名单目录（设置后仅允许这些目录）
}

// DefaultBlacklist 默认敏感文件黑名单
var DefaultBlacklist = []string{
    `^/etc/shadow$`,
    `^/etc/gshadow$`,
    `^/etc/sudoers$`,
    `^/etc/sudoers\.d/`,
    `^/etc/ssh/ssh_host_`,
    `/\.ssh/id_`,
    `/\.ssh/authorized_keys$`,
    `/\.gnupg/`,
    `/\.aws/credentials$`,
    `/\.aws/config$`,
    `/\.kube/config$`,
    `/\.docker/config\.json$`,
    `/\.[a-z]*_history$`,
    `/\.env$`,
    `/\.env\.`,
    `/secrets?\.(ya?ml|json|toml|properties|conf)$`,
    `/credentials?\.(ya?ml|json|toml|properties|conf)$`,
    `/\.git/config$`,
    `/\.gitconfig$`,
    `/\.netrc$`,
    `/\.pgpass$`,
    `/private[_-]?key`,
    `\.pem$`,
    `\.key$`,
}

// ValidatePath 路径安全校验（核心安全函数）
//
// 执行以下校验：
// 1. filepath.Clean 规范化路径
// 2. 解析为绝对路径并校验在 RootPath 内
// 3. Lstat 检查符号链接 → EvalSymlinks 验证真实路径仍在 RootPath 内
// 4. 黑名单正则匹配
// 5. 白名单检查（若配置）
func (sc *SecurityConfig) ValidatePath(requestedPath string) (string, error) {
    // 1. 清理路径
    cleaned := filepath.Clean(requestedPath)
    if cleaned == "" || cleaned == "." {
        cleaned = "/"
    }

    // 2. 拼接 root 并解析为绝对路径
    var absPath string
    if filepath.IsAbs(cleaned) {
        absPath = filepath.Join(sc.RootPath, cleaned)
    } else {
        absPath = filepath.Join(sc.RootPath, cleaned)
    }
    absPath = filepath.Clean(absPath)

    // 3. Prefix 校验（防止 filepath.Join 的 .. 逃逸）
    rootClean := filepath.Clean(sc.RootPath)
    if absPath != rootClean && !strings.HasPrefix(absPath, rootClean+string(os.PathSeparator)) {
        return "", ErrPathTraversal
    }

    // 4. 符号链接安全检查
    if info, err := os.Lstat(absPath); err == nil {
        if info.Mode()&os.ModeSymlink != 0 {
            realPath, err := filepath.EvalSymlinks(absPath)
            if err != nil {
                return "", fmt.Errorf("resolve symlink: %w", err)
            }
            realPath = filepath.Clean(realPath)
            if realPath != rootClean && !strings.HasPrefix(realPath, rootClean+string(os.PathSeparator)) {
                return "", ErrSymlinkTraversal
            }
        }
    }
    // 注意：Lstat 返回 error（文件不存在）时跳过符号链接检查
    // 这在 mkdir/upload 创建新文件时是正常的

    // 5. 黑名单检查（对 absPath 执行）
    for _, re := range sc.BlacklistRegexp {
        if re.MatchString(absPath) {
            return "", ErrAccessDenied
        }
    }

    // 6. 白名单检查（若配置）
    if len(sc.WhitelistPaths) > 0 {
        allowed := false
        for _, wp := range sc.WhitelistPaths {
            wpClean := filepath.Clean(wp)
            if absPath == wpClean || strings.HasPrefix(absPath, wpClean+string(os.PathSeparator)) {
                allowed = true
                break
            }
        }
        if !allowed {
            return "", ErrAccessDenied
        }
    }

    return absPath, nil
}

// ValidateWrite 写操作前额外检查
func (sc *SecurityConfig) ValidateWrite(requestedPath string) (string, error) {
    if sc.ReadOnly {
        return "", ErrReadOnly
    }
    return sc.ValidatePath(requestedPath)
}

// ValidateFileSize 文件大小检查
func (sc *SecurityConfig) ValidateFileSize(size int64) error {
    if sc.MaxFileSize > 0 && size > sc.MaxFileSize {
        return fmt.Errorf("file size %d exceeds maximum %d bytes", size, sc.MaxFileSize)
    }
    return nil
}
```

**安全校验流程图**：

```
请求路径
   │
   ▼
filepath.Clean() ──── 去除 . / .. / 多余斜杠
   │
   ▼
filepath.Join(root, path) ──── 拼接根目录
   │
   ▼
strings.HasPrefix(abs, root) ──── 路径穿越检测
   │  ✗ → 拒绝 (ErrPathTraversal)
   ▼
os.Lstat() ──── 是否符号链接？
   │  是 → EvalSymlinks → HasPrefix 再次检查
   │        ✗ → 拒绝 (ErrSymlinkTraversal)
   ▼
黑名单正则匹配 ──── 敏感文件？
   │  是 → 拒绝 (ErrAccessDenied)
   ▼
白名单检查（若配置） ──── 在白名单内？
   │  否 → 拒绝 (ErrAccessDenied)
   ▼
✓ 通过，返回安全绝对路径
```

#### 4.1.4 handler.go — 文件操作处理器

```go
package fileserver

import (
    "encoding/base64"
    "encoding/json"
    "fmt"
    "io"
    "log/slog"
    "os"
    "path/filepath"
    "sort"
    "sync"
)

const ChunkSize = 256 * 1024  // 256KB per chunk

// Handler 处理所有文件操作消息
type Handler struct {
    config    *SecurityConfig
    uploads   map[string]*uploadState  // requestID → upload state
    uploadsMu sync.Mutex
}

type uploadState struct {
    file     *os.File
    tempPath string
    destPath string
    received int64
    expected int64
}

// NewHandler 创建文件操作处理器
func NewHandler(config *SecurityConfig) *Handler {
    return &Handler{
        config:  config,
        uploads: make(map[string]*uploadState),
    }
}

// HandleMessage 路由文件操作消息
func (h *Handler) HandleMessage(data []byte, send func(interface{})) {
    var msg map[string]interface{}
    if err := json.Unmarshal(data, &msg); err != nil {
        slog.Error("fileserver: unmarshal error", "error", err)
        return
    }

    msgType, _ := msg["type"].(string)
    
    switch msgType {
    case "file_list":
        h.handleList(data, send)
    case "file_download":
        h.handleDownload(data, send)
    case "file_upload_start":
        h.handleUploadStart(data, send)
    case "file_upload_chunk":
        h.handleUploadChunk(data, send)
    case "file_mkdir":
        h.handleMkdir(data, send)
    case "file_delete":
        h.handleDelete(data, send)
    case "file_rename":
        h.handleRename(data, send)
    }
}
```

**handleList 实现要点**：
- `os.ReadDir(absPath)` 获取目录条目
- 截断到 `config.MaxListEntries` 条
- 对每个条目调用 `os.Stat` 获取 size/mode/modTime
- 目录排在文件前面，同类按名称排序

**handleDownload 实现要点**：
- `os.Open(absPath)` + `os.Stat` 获取文件大小
- 循环读取 256KB 块，Base64 编码后发送 `FileDownloadChunk`
- 最后一块设 `Final: true`
- **大文件限制**：超过 `config.MaxFileSize` 拒绝下载

**handleUploadStart 实现要点**：
- `config.ValidateWrite(path)` + `config.ValidateFileSize(size)`
- 创建临时文件 `filepath.Dir(destPath) + "/.easyshell_upload_" + requestID`
- 存入 `uploads` map

**handleUploadChunk 实现要点**：
- 从 `uploads` map 获取 state
- Base64 解码 → 写入临时文件
- `Final: true` 时 `os.Rename(tempPath, destPath)` 原子提交
- 清理 `uploads` map

**handleDelete 实现要点**：
- `config.ValidateWrite(path)`
- `os.Stat` 判断文件/目录
- 目录用 `os.RemoveAll`（仅允许空目录？或确认非空目录删除？— **默认 `os.RemoveAll`，Server 端二次确认**）
- **特殊保护**：拒绝删除 `/`、`/etc`、`/var`、`/usr`、`/home` 等系统顶级目录

#### 4.1.5 ws/client.go 修改

在 `handleMessage` 中新增 `file_*` 消息分发：

```go
// 现有
case "terminal_open", "terminal_input", "terminal_resize", "terminal_close":
    c.handleTerminalMessage(data, msg)

// 新增
case "file_list", "file_download", "file_upload_start", "file_upload_chunk",
     "file_mkdir", "file_delete", "file_rename":
    c.handleFileMessage(data)
```

```go
func (c *Client) handleFileMessage(data []byte) {
    c.fileHandler.HandleMessage(data, func(resp interface{}) {
        if err := c.sendJSON(resp); err != nil {
            slog.Error("failed to send file response", "error", err)
        }
    })
}
```

#### 4.1.6 config.go 扩展

```go
type AgentConfig struct {
    ID   string     `yaml:"id"`
    File FileConfig `yaml:"file"`
}

type FileConfig struct {
    Enabled        bool     `yaml:"enabled"`         // 默认 true
    Root           string   `yaml:"root"`            // 默认 "/"
    ReadOnly       bool     `yaml:"readonly"`        // 默认 false
    MaxSizeMB      int      `yaml:"max-size-mb"`     // 默认 500
    MaxListEntries int      `yaml:"max-list-entries"` // 默认 1000
    Blacklist      []string `yaml:"blacklist"`       // 追加黑名单正则
    Whitelist      []string `yaml:"whitelist"`       // 白名单目录
}
```

### 4.2 Server 端

#### 4.2.1 新增文件

| 文件路径 | 描述 |
|----------|------|
| `controller/FileProxyController.java` | REST API 端点 |
| `service/FileProxyService.java` | 接口 |
| `service/impl/FileProxyServiceImpl.java` | 消息编排 + 响应聚合 |
| `model/vo/FileInfoVO.java` | 文件信息 VO |
| `model/dto/FileRenameRequest.java` | 重命名 DTO |

#### 4.2.2 FileProxyController.java

```java
package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.FileRenameRequest;
import com.easyshell.server.model.vo.FileInfoVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.FileProxyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/files")
@RequiredArgsConstructor
public class FileProxyController {

    private final FileProxyService fileProxyService;
    private final AuditLogService auditLogService;

    @GetMapping
    public R<List<FileInfoVO>> listFiles(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "/") String path,
            HttpServletRequest request) {
        // 1. 校验 Agent 在线
        // 2. 通过 WS 发送 file_list 请求，等待响应
        // 3. 审计日志
        List<FileInfoVO> files = fileProxyService.listFiles(agentId, path);
        auditLog(request, agentId, "FILE_LIST", path, "SUCCESS");
        return R.ok(files);
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            HttpServletRequest request) {
        // 1. 通过 WS 发送 file_download 请求
        // 2. 收集 Agent 返回的分块数据
        // 3. StreamingResponseBody 流式写入 HTTP Response
        // 4. 审计日志
        auditLog(request, agentId, "FILE_DOWNLOAD", path, "SUCCESS");
        return fileProxyService.downloadFile(agentId, path);
    }

    @PostMapping("/upload")
    public R<Void> uploadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        // 1. 校验文件大小
        // 2. 分块发送 file_upload_start + file_upload_chunk
        // 3. 等待 Agent 确认
        // 4. 审计日志
        fileProxyService.uploadFile(agentId, path, file);
        auditLog(request, agentId, "FILE_UPLOAD", path, "SUCCESS");
        return R.ok();
    }

    @PostMapping("/mkdir")
    public R<Void> mkdir(
            @PathVariable String agentId,
            @RequestParam String path,
            HttpServletRequest request) {
        fileProxyService.mkdir(agentId, path);
        auditLog(request, agentId, "FILE_MKDIR", path, "SUCCESS");
        return R.ok();
    }

    @DeleteMapping
    public R<Void> deleteFile(
            @PathVariable String agentId,
            @RequestParam String path,
            HttpServletRequest request) {
        fileProxyService.delete(agentId, path);
        auditLog(request, agentId, "FILE_DELETE", path, "SUCCESS");
        return R.ok();
    }

    @PutMapping("/rename")
    public R<Void> rename(
            @PathVariable String agentId,
            @RequestBody FileRenameRequest renameRequest,
            HttpServletRequest request) {
        fileProxyService.rename(agentId, renameRequest.getOldPath(), renameRequest.getNewPath());
        auditLog(request, agentId, "FILE_RENAME",
                renameRequest.getOldPath() + " → " + renameRequest.getNewPath(), "SUCCESS");
        return R.ok();
    }

    // 复用现有 AuditLogService
    private void auditLog(HttpServletRequest request, String agentId,
                          String action, String path, String result) {
        // 从 SecurityContext 获取 userId/username
        // 从 request 获取 clientIp
        auditLogService.log(userId, username, action, "FILE", agentId, path, clientIp, result);
    }
}
```

#### 4.2.3 FileProxyServiceImpl.java — 请求-响应关联

```java
package com.easyshell.server.service.impl;

import com.easyshell.server.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FileProxyServiceImpl implements FileProxyService {

    private final AgentWebSocketHandler agentHandler;

    // requestId → CompletableFuture，用于关联 WS 请求和响应
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    // 超时时间
    private static final long LIST_TIMEOUT_SECONDS = 10;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 300;  // 5 分钟
    private static final long UPLOAD_TIMEOUT_SECONDS = 300;
    private static final long OPERATION_TIMEOUT_SECONDS = 10;

    @Override
    public List<FileInfoVO> listFiles(String agentId, String path) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            // 发送请求到 Agent
            String msg = objectMapper.writeValueAsString(Map.of(
                "type", "file_list",
                "requestId", requestId,
                "path", path
            ));
            boolean sent = agentHandler.sendToAgent(agentId, msg);
            if (!sent) throw new BusinessException("Agent not connected");

            // 等待响应
            String response = future.get(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 解析 FileListResponse → List<FileInfoVO>
            return parseFileListResponse(response);
        } catch (TimeoutException e) {
            throw new BusinessException("File operation timed out");
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    // Agent 响应回调（由 AgentWebSocketHandler 调用）
    public void handleFileResponse(String requestId, String responseJson) {
        CompletableFuture<String> future = pendingRequests.get(requestId);
        if (future != null) {
            future.complete(responseJson);
        }
    }
}
```

#### 4.2.4 AgentWebSocketHandler.java 修改

新增 `file_*_result` 消息分发：

```java
// 新增在 handleTextMessage 的 switch 中
case "file_list_result", "file_download_chunk", "file_result" -> {
    String requestId = node.has("requestId") ? node.get("requestId").asText() : "";
    if (fileProxyService != null) {
        fileProxyService.handleFileResponse(requestId, message.getPayload());
    }
}
```

#### 4.2.5 SecurityConfig.java 修改

文件 API 端点需要认证（`/api/v1/agents/{agentId}/files/**` 不在 `permitAll` 中，已自动需要 JWT）。

**无需改动** — 当前 `anyRequest().authenticated()` 已覆盖。

#### 4.2.6 FileInfoVO.java

```java
package com.easyshell.server.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileInfoVO {
    private String name;
    private boolean isDir;
    private long size;
    private String mode;       // "drwxr-xr-x"
    private long modTime;      // Unix 时间戳（毫秒）
}
```

#### 4.2.7 FileRenameRequest.java

```java
package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileRenameRequest {
    @NotBlank(message = "Old path is required")
    private String oldPath;

    @NotBlank(message = "New path is required")
    private String newPath;
}
```

### 4.3 前端

#### 4.3.1 新增文件

| 文件 | 描述 |
|------|------|
| `src/pages/terminal/components/FileManager.tsx` | 文件管理侧边栏主组件 |
| `src/pages/terminal/components/FileTree.tsx` | 文件列表/树组件 |
| `src/pages/terminal/hooks/useFileManager.ts` | 文件操作 API Hook |

#### 4.3.2 useFileManager.ts

```typescript
import { useState, useCallback } from 'react';
import { message } from 'antd';

interface FileInfo {
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
  navigate: (path: string) => Promise<void>;
  upload: (path: string, file: File) => Promise<void>;
  download: (path: string, fileName: string) => Promise<void>;
  mkdir: (path: string) => Promise<void>;
  remove: (path: string) => Promise<void>;
  rename: (oldPath: string, newPath: string) => Promise<void>;
}

export function useFileManager(agentId: string): UseFileManagerReturn {
  // REST API 调用
  // GET    /api/v1/agents/${agentId}/files?path=${encodeURIComponent(path)}
  // GET    /api/v1/agents/${agentId}/files/download?path=${encodeURIComponent(path)}
  // POST   /api/v1/agents/${agentId}/files/upload?path=${encodeURIComponent(path)}
  // POST   /api/v1/agents/${agentId}/files/mkdir?path=${encodeURIComponent(path)}
  // DELETE /api/v1/agents/${agentId}/files?path=${encodeURIComponent(path)}
  // PUT    /api/v1/agents/${agentId}/files/rename
}
```

#### 4.3.3 FileManager.tsx

```typescript
interface FileManagerProps {
  agentId: string;
  onClose: () => void;
}
```

**布局**：
- 宽度 360px，右侧固定
- 顶部：路径面包屑 + 手动输入框 + 刷新按钮
- 中部工具栏：上传按钮、新建文件夹按钮
- 主体：文件列表（Ant Design `List` 或 `Table`）
- 每项：图标（文件夹/文件 by 类型）+ 名称 + 大小 + 修改时间 + 操作按钮（下载/重命名/删除）
- 上传区域：Ant Design `Upload` 组件 + Dragger（拖拽区）

**关键交互**：
- 点击文件夹 → navigate 进入
- 点击文件 → 无操作（不是编辑器）
- 面包屑点击 → 跳转到父目录
- 删除 → `Modal.confirm` 二次确认
- 上传 → 进度条显示在列表底部

#### 4.3.4 i18n 新增键

```json
{
  "terminal.files.title": "File Manager",
  "terminal.files.upload": "Upload",
  "terminal.files.download": "Download",
  "terminal.files.newFolder": "New Folder",
  "terminal.files.delete": "Delete",
  "terminal.files.rename": "Rename",
  "terminal.files.refresh": "Refresh",
  "terminal.files.deleteConfirm": "Are you sure you want to delete \"{{name}}\"?",
  "terminal.files.uploadSuccess": "File uploaded successfully",
  "terminal.files.downloadStarted": "Download started",
  "terminal.files.operationFailed": "Operation failed: {{error}}",
  "terminal.files.pathPlaceholder": "Enter path...",
  "terminal.files.newFolderName": "New Folder Name",
  "terminal.files.emptyDirectory": "Empty directory",
  "terminal.files.fileTooLarge": "File size exceeds the maximum limit ({{max}}MB)"
}
```

---

## 5. 安全规格

### 5.1 威胁-缓解矩阵

| # | 威胁 | 影响 | 缓解措施 | 实现位置 |
|---|------|------|----------|----------|
| T1 | **路径穿越** — `../../etc/shadow` | 严重：敏感文件泄露 | `filepath.Clean` + `HasPrefix` + 符号链接检查 | `security.go:ValidatePath` |
| T2 | **路径穿越变种** — URL 编码 `%2e%2e%2f`、空字节 `%00` | 严重：绕过路径检查 | Server 端 `URLDecoder.decode` 后再传给 Agent；Go `filepath.Clean` 处理空字节 | `FileProxyController` + `security.go` |
| T3 | **符号链接逃逸** — `/tmp/link → /etc/shadow` | 严重：通过合法路径读取敏感文件 | `os.Lstat` 检测符号链接 → `EvalSymlinks` 解析真实路径 → 再次 Prefix 检查 | `security.go:ValidatePath` 第 4 步 |
| T4 | **敏感文件直接访问** | 高：凭证/密钥泄露 | 正则黑名单（24 条默认规则）+ 可选白名单模式 | `security.go:DefaultBlacklist` |
| T5 | **大文件 DoS** | 高：磁盘/内存耗尽 | Server 端 multipart 大小限制 + Agent 端 `MaxFileSize` 检查 | `application.yml` + `security.go:ValidateFileSize` |
| T6 | **并发上传轰炸** | 高：资源耗尽 | Server 端限流（`@RateLimiter` 或 Semaphore，最大 3 并发上传/Agent） | `FileProxyServiceImpl` |
| T7 | **未授权 Agent 文件操作** | 严重：任意文件读写 | JWT 认证（Browser→Server）+ Agent 文件操作 `config.Enabled` 开关 | `SecurityConfig.java` + `config.go` |
| T8 | **WebSocket 消息伪造** | 高：注入文件操作消息 | Agent WS 仅接受 Server 连接（单一来源）；Server 端仅接受已认证用户的文件请求 | 架构约束 |
| T9 | **临时文件残留** | 中：磁盘空间泄漏 | 上传超时清理临时文件；Agent 启动时清理 `.easyshell_upload_*` 残留 | `handler.go` |
| T10 | **目录列表信息泄露** | 中：暴露系统结构 | `MaxListEntries` 限制条目数；黑名单目录不可列出内容 | `security.go` + `handler.go` |
| T11 | **TOCTOU 竞态** | 中：在校验和操作之间文件被替换 | 使用 `O_NOFOLLOW` 打开文件（不跟随符号链接）| `handler.go` |
| T12 | **系统目录误删** | 严重：系统不可用 | 硬编码保护列表：`/`, `/bin`, `/sbin`, `/etc`, `/usr`, `/var`, `/lib`, `/lib64`, `/boot`, `/proc`, `/sys`, `/dev` | `handler.go:handleDelete` |

### 5.2 数据流安全

```
Browser ──HTTPS──► Server ──WSS──► Agent
   │                │                │
   │ JWT Token      │ Session Auth   │ 配置文件权限控制
   │ in Header      │ (WS 已建立)    │ (file.enabled,
   │                │                │  file.readonly,
   ▼                ▼                │  blacklist, whitelist)
验证用户身份     验证 Agent 在线      ▼
+ 权限检查      + 转发消息          执行文件操作
                                    + 安全校验
```

### 5.3 审计日志规格

**每条文件操作审计记录包含**：

| 字段 | 来源 | 示例 |
|------|------|------|
| `userId` | `SecurityContextHolder` | `1` |
| `username` | `SecurityContextHolder` | `admin` |
| `action` | 硬编码常量 | `FILE_DOWNLOAD` |
| `resourceType` | 固定值 `"FILE"` | `FILE` |
| `resourceId` | agentId | `ag_server01` |
| `detail` | 操作路径/描述 | `/var/log/app.log` |
| `ip` | `request.getRemoteAddr()` | `192.168.1.100` |
| `result` | `SUCCESS` / `FAILED` | `SUCCESS` |

**审计事件完整列表**：

| action | 触发条件 |
|--------|----------|
| `FILE_LIST` | 列出目录 |
| `FILE_DOWNLOAD` | 下载文件 |
| `FILE_UPLOAD` | 上传文件 |
| `FILE_DELETE` | 删除文件或目录 |
| `FILE_MKDIR` | 创建目录 |
| `FILE_RENAME` | 重命名 |
| `FILE_ACCESS_DENIED` | 任何安全校验失败（黑名单、路径穿越、只读等） |

**`FILE_ACCESS_DENIED` 特别说明**：即使操作被拒绝，也必须记录审计日志，且 detail 中包含拒绝原因。这对安全事件追溯至关重要。

### 5.4 安全失败行为

| 场景 | 行为 |
|------|------|
| Agent 离线 | 返回 `400 Agent not connected`，不重试 |
| Agent `file.enabled=false` | Agent 端静默忽略文件消息，返回 `error: file operations disabled` |
| 路径穿越检测 | 返回 `403 Access denied`，记录 `FILE_ACCESS_DENIED` 审计 |
| 黑名单匹配 | 返回 `403 Access denied`（不暴露黑名单规则），记录审计 |
| 上传超限 | 返回 `413 File too large`，不写入任何数据 |
| 操作超时 | 返回 `504 Operation timed out`，Agent 端清理临时文件 |

---

## 6. 数据库变更

### 6.1 无新增表

文件操作为无状态代理，不持久化文件元数据。审计日志复用现有 `audit_log` 表。

### 6.2 audit_log 表（现有，无 DDL 改动）

新增 action 值：`FILE_LIST`, `FILE_DOWNLOAD`, `FILE_UPLOAD`, `FILE_DELETE`, `FILE_MKDIR`, `FILE_RENAME`, `FILE_ACCESS_DENIED`

`resourceType` 新增值：`FILE`

---

## 7. 配置变更

### 7.1 Agent 配置（`configs/agent.yaml`）

```yaml
# 新增配置项
agent:
  id: ""
  file:                              # 新增 section
    enabled: true                    # 是否启用文件操作（默认 true）
    root: "/"                        # 文件操作根目录（默认 /）
    readonly: false                  # 只读模式（默认 false）
    max-size-mb: 500                 # 单文件最大 MB（默认 500）
    max-list-entries: 1000           # 目录列表最大条目数（默认 1000）
    blacklist: []                    # 追加黑名单正则（在默认黑名单基础上）
    whitelist: []                    # 白名单目录（设置后仅允许访问这些目录）
```

**向后兼容**：未配置 `file` section 时使用所有默认值，现有 Agent 无需改配置文件即可升级。

### 7.2 Server 配置（`application.yml`）

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 500MB          # 与 Agent 端保持一致
      max-request-size: 510MB       # 稍大于 max-file-size
```

### 7.3 前端配置

无新增配置。终端设置持久化在 `localStorage` 中（Phase 3 功能，本 spec 不涉及）。

---

## 8. API 规格

### 8.1 列出目录

```
GET /api/v1/agents/{agentId}/files?path=/var/log
Authorization: Bearer {jwt-token}

Response 200:
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "name": "app.log",
      "isDir": false,
      "size": 1048576,
      "mode": "-rw-r--r--",
      "modTime": 1709510400000
    },
    {
      "name": "nginx",
      "isDir": true,
      "size": 4096,
      "mode": "drwxr-xr-x",
      "modTime": 1709424000000
    }
  ]
}
```

### 8.2 下载文件

```
GET /api/v1/agents/{agentId}/files/download?path=/var/log/app.log
Authorization: Bearer {jwt-token}

Response 200:
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="app.log"
Content-Length: 1048576

<binary stream>
```

### 8.3 上传文件

```
POST /api/v1/agents/{agentId}/files/upload?path=/home/user/uploads
Authorization: Bearer {jwt-token}
Content-Type: multipart/form-data; boundary=----

------
Content-Disposition: form-data; name="file"; filename="deploy.sh"
Content-Type: application/octet-stream

<binary>
------

Response 200:
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 8.4 创建目录

```
POST /api/v1/agents/{agentId}/files/mkdir?path=/home/user/new-dir
Authorization: Bearer {jwt-token}

Response 200:
{ "code": 200, "message": "success", "data": null }
```

### 8.5 删除文件/目录

```
DELETE /api/v1/agents/{agentId}/files?path=/home/user/old-file.txt
Authorization: Bearer {jwt-token}

Response 200:
{ "code": 200, "message": "success", "data": null }
```

### 8.6 重命名

```
PUT /api/v1/agents/{agentId}/files/rename
Authorization: Bearer {jwt-token}
Content-Type: application/json

{
  "oldPath": "/home/user/old-name.txt",
  "newPath": "/home/user/new-name.txt"
}

Response 200:
{ "code": 200, "message": "success", "data": null }
```

### 8.7 错误响应

```json
// 403 — 安全拦截
{ "code": 403, "message": "Access denied", "data": null }

// 400 — Agent 离线
{ "code": 400, "message": "Agent not connected", "data": null }

// 413 — 文件过大
{ "code": 413, "message": "File size exceeds maximum 500MB", "data": null }

// 504 — 操作超时
{ "code": 504, "message": "Operation timed out", "data": null }

// 500 — Agent 端错误
{ "code": 500, "message": "File operation failed: permission denied", "data": null }
```

---

## 9. 错误处理

### 9.1 错误分类

| 层 | 错误类型 | 处理策略 |
|----|----------|----------|
| **前端** | 网络错误 | `message.error(i18n 错误提示)` + 重试按钮 |
| **前端** | 业务错误（403/413/504） | `message.error(response.message)` |
| **Server** | Agent 离线 | `BusinessException("Agent not connected")` → 400 |
| **Server** | WS 发送失败 | `BusinessException("Failed to communicate with agent")` → 502 |
| **Server** | 响应超时 | `BusinessException("Operation timed out")` → 504 |
| **Agent** | 路径安全拦截 | 返回 `FileOperationResult{success: false, error: "access denied"}` |
| **Agent** | 文件系统错误 | 返回 `FileOperationResult{success: false, error: err.Error()}` |
| **Agent** | 磁盘满 | 返回错误 + 清理临时文件 |

### 9.2 超时设置

| 操作 | Server 等待超时 | Agent 端操作超时 |
|------|----------------|-----------------|
| 列目录 | 10s | 不限 |
| 下载 | 300s（5 分钟） | 不限（流式） |
| 上传 | 300s（5 分钟） | 不限（流式） |
| 创建/删除/重命名 | 10s | 不限 |

### 9.3 临时文件清理

**正常流程**：上传完成 → `os.Rename(temp, dest)` → 删除无残留

**异常流程**：
1. 上传中断（WS 断开）→ Agent 端 `uploadState` 超时（5 分钟无新分块）→ 删除临时文件
2. Agent 重启 → `main.go` 启动时扫描 `.easyshell_upload_*` 文件并删除
3. Server 端 `CompletableFuture` 超时 → `pendingRequests` 自动清理

---

## 10. 测试计划

### 10.1 安全测试（必须通过）

| 编号 | 测试用例 | 预期结果 |
|------|----------|----------|
| S01 | `path=../../../etc/shadow` | 403 + 审计日志 `FILE_ACCESS_DENIED` |
| S02 | `path=%2e%2e%2f%2e%2e%2fetc%2fshadow` | 403 |
| S03 | `path=/tmp/link`（符号链接→`/etc/shadow`）| 403 |
| S04 | `path=/etc/shadow` 直接访问 | 403 |
| S05 | `path=/home/user/.ssh/id_rsa` | 403 |
| S06 | `path=/home/user/.env` | 403 |
| S07 | `path=/home/user/.bash_history` | 403 |
| S08 | `path=/home/user/.aws/credentials` | 403 |
| S09 | 上传 600MB 文件（超限） | 413 |
| S10 | 无 JWT Token 访问文件 API | 401 |
| S11 | Agent `file.enabled=false` | 返回 `file operations disabled` |
| S12 | Agent `file.readonly=true` + 上传/删除 | 403 |
| S13 | 删除 `/` | 403（系统目录保护） |
| S14 | 删除 `/etc` | 403 |
| S15 | 并发 10 个上传（限制 3） | 3 个成功，7 个排队或拒绝 |
| S16 | `path=/home/user/file%00.txt` 空字节注入 | 403 或路径被清理 |

### 10.2 功能测试

| 编号 | 测试用例 | 预期结果 |
|------|----------|----------|
| F01 | 列出 `/tmp` 目录 | 返回文件列表 |
| F02 | 上传 1KB 文本文件 | 上传成功，可列出 |
| F03 | 上传 100MB 文件 | 上传成功，有进度 |
| F04 | 下载文件 | 触发浏览器 SaveAs |
| F05 | 创建目录 | 目录出现在列表中 |
| F06 | 重命名文件 | 旧名消失，新名出现 |
| F07 | 删除文件 | 文件从列表消失 |
| F08 | 打开 8 个终端标签 | 全部独立工作 |
| F09 | 打开第 9 个标签 | 被禁止，tooltip 提示 |
| F10 | 标签切换 | 内容保留，不断线 |
| F11 | 终端搜索 | 高亮匹配文本 |
| F12 | 全屏切换 | 正常进入/退出 |
| F13 | Agent 离线时文件操作 | 返回 `Agent not connected` |
| F14 | Agent 离线时终端标签 | 连接状态变红 |
| F15 | 所有文件操作审计日志 | `audit_log` 表有完整记录 |

### 10.3 兼容性测试

| 场景 | 预期 |
|------|------|
| 旧版 Agent（无 file handler）+ 新 Server | 文件操作返回超时错误，终端功能不受影响 |
| 新 Agent（无 file config）+ 新 Server | 使用默认配置，文件操作正常 |
| 移动端浏览器 | 工具栏响应式折叠，文件管理面板全宽覆盖 |

---

## 11. 部署与兼容性

### 11.1 版本发布策略

- **Agent 二进制**：版本号 ≥ `0.2.0` 支持文件操作
- **Server/Web**：通过 Docker image 发布
- **向后兼容**：旧 Agent 不处理 `file_*` 消息（静默忽略），Server 端超时后返回错误

### 11.2 数据库迁移

无 DDL 改动。`audit_log` 表的新 action 值仅为数据层面新增，不影响表结构。

### 11.3 Docker Compose

无配置改动。Agent 文件操作配置通过 `configs/agent.yaml` 管理。

### 11.4 发布检查清单

- [ ] Agent 二进制包含 `fileserver` 包
- [ ] Server REST 端点可访问
- [ ] `spring.servlet.multipart.max-file-size` 配置正确
- [ ] 安全测试 S01-S16 全部通过
- [ ] 功能测试 F01-F15 全部通过
- [ ] 审计日志验证完整
- [ ] 旧 Agent 兼容性验证
