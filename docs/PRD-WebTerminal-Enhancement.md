# EasyShell Web Terminal 增强 — PRD

> 文档版本：v1.0  
> 创建日期：2026-03-04  
> 状态：Draft

---

## 1. 背景与现状

### 1.1 当前架构

```
Browser (xterm.js) ──WebSocket──► Server (TerminalWebSocketHandler) ──WebSocket──► Agent (Go PTY via creack/pty)
```

三层代理模型：浏览器 ↔ Spring Boot Server ↔ Go Agent。消息类型通过 JSON 封装，Server 做纯转发，Agent 端创建真实 PTY 会话。

### 1.2 当前能力

| 能力 | 状态 |
|------|------|
| 交互式 Shell（PTY） | ✅ 已实现 |
| 终端自适应缩放（FitAddon） | ✅ 已实现 |
| 可点击链接（WebLinksAddon） | ✅ 已实现 |
| 连接状态指示 | ✅ 已实现 |
| 暗色主题 | ✅ 已实现（硬编码） |
| 5000 行滚动缓冲 | ✅ 已实现 |
| 移动端适配 | ✅ 基础适配 |

### 1.3 缺失能力

| 能力 | 现状 |
|------|------|
| 文件上传/下载 | ❌ 完全缺失 |
| 多标签页/分屏 | ❌ 单一全屏终端 |
| 终端内搜索 | ❌ 无搜索功能 |
| 主题/字体自定义 | ❌ 硬编码主题 |
| 会话保持/断线重连 | ❌ 断线即丢失 |
| 复制粘贴增强 | ❌ 仅浏览器默认行为 |
| 全屏模式 | ❌ 不支持 |
| 快捷连接 | ❌ 必须从主机详情跳转 |

---

## 2. 目标

打造一个**生产级 Web 终端**，对标 JumpServer / 1Panel 的终端体验，覆盖日常运维 80% 的场景，不再需要打开本地 SSH 客户端。

### 核心原则

- **渐进增强**：不破坏现有架构，在现有 WebSocket 消息协议上扩展
- **Agent 轻量**：文件操作由 Agent 端实现（Go），不引入额外依赖
- **前端驱动**：大部分交互逻辑在前端完成，后端保持薄转发层
- **安全第一**：文件操作需严格鉴权，防止越权访问和数据泄露

---

## 3. 功能规划

### P0 — 必须实现（本次迭代）

#### 3.1 SFTP 文件管理器

**概述**：在终端页面侧边提供可视化文件浏览器，支持上传/下载文件。

**前端**：
- 终端右侧可折叠侧边栏（Drawer 或 Split Pane），默认收起
- 点击工具栏「文件」按钮展开文件管理面板
- 树形/列表展示远程目录结构，支持面包屑路径导航
- 文件操作：
  - **上传**：点击上传按钮或拖拽文件到面板区域，支持多文件
  - **下载**：点击文件条目的下载按钮，浏览器触发下载
  - **新建文件夹**
  - **删除**（二次确认）
  - **重命名**
- 上传/下载进度条，支持大文件分块
- 路径输入框可手动跳转目录

**后端（Server）**：
- 新增 REST 端点（非 WebSocket，便于处理二进制流）：
  - `GET /api/agents/{agentId}/files?path=` — 列出目录
  - `GET /api/agents/{agentId}/files/download?path=` — 下载文件（流式）
  - `POST /api/agents/{agentId}/files/upload?path=` — 上传文件（multipart）
  - `POST /api/agents/{agentId}/files/mkdir?path=` — 创建目录
  - `DELETE /api/agents/{agentId}/files?path=` — 删除文件/目录
  - `PUT /api/agents/{agentId}/files/rename` — 重命名
- Server 将请求转发给 Agent（通过 Agent 已有的 HTTP 回调通道或新建文件操作 WebSocket 消息类型）

**Agent（Go）**：
- 新增文件操作 HTTP 端点或 WebSocket 消息处理：
  - 目录列表（`os.ReadDir` + `os.Stat`）
  - 文件读取（流式写入响应）
  - 文件写入（接收分块数据写入磁盘）
  - 创建/删除/重命名（标准 `os` 包操作）
- 安全限制：不允许遍历超出配置的根目录（默认 `/`，可配置）

**数据流（上传示例）**：
```
Browser ──multipart POST──► Server /api/agents/{id}/files/upload
Server ──forward (HTTP/WS)──► Agent (write to disk)
Agent ──response──► Server ──response──► Browser
```

**数据流（下载示例）**：
```
Browser ──GET──► Server /api/agents/{id}/files/download?path=/var/log/app.log
Server ──forward──► Agent (read file, stream)
Agent ──stream──► Server ──stream──► Browser (SaveAs dialog)
```

#### 3.2 终端多标签页

**概述**：支持在同一页面内打开多个终端会话标签页。

**前端**：
- 终端区域顶部增加标签栏（Tabs）
- 默认打开一个终端标签，工具栏「+」按钮可新增标签
- 每个标签独立 WebSocket 连接 + 独立 xterm.js 实例
- 标签可关闭（关闭时断开该标签的 WebSocket）
- 标签支持重命名（双击标签名编辑）
- 最多支持 **8 个并发标签**（前端限制）

**后端**：
- 无需改动 — 当前架构已支持同一 Agent 的多个并发终端会话（`sessionMap` 基于 sessionId 隔离）

**Agent**：
- 无需改动 — `TerminalManager` 已支持多会话并发

#### 3.3 终端内搜索

**概述**：支持 Ctrl+Shift+F 在终端滚动缓冲区中搜索文本。

**前端**：
- 加载 `@xterm/addon-search`
- 工具栏增加搜索按钮，或快捷键 `Ctrl+Shift+F` 唤出搜索框
- 搜索框支持：向上/向下查找、大小写敏感切换、正则匹配
- 匹配项高亮显示

**后端/Agent**：无需改动（纯前端功能）

#### 3.4 终端工具栏

**概述**：在终端区域顶部增加工具栏，集中放置功能按钮。

**工具栏按钮**：
| 按钮 | 功能 |
|------|------|
| 📁 文件 | 打开/关闭 SFTP 文件管理面板 |
| 🔍 搜索 | 打开终端内搜索框 |
| ⊞ 新标签 | 新增终端标签 |
| ⛶ 全屏 | 进入/退出全屏模式 |
| ⚙ 设置 | 打开终端设置（字体、主题等） |
| 📋 复制 | 复制当前选中内容 |
| 📥 粘贴 | 从剪贴板粘贴 |

---

### P1 — 应当实现（下一迭代）

#### 3.5 终端主题与字体设置

- 提供 3-5 个预设主题（当前暗色、Dracula、Solarized Dark、Monokai、Light）
- 字体大小可调（12-20px 滑块）
- 字体族可选（JetBrains Mono、Fira Code、Cascadia Code、Courier New）
- 设置持久化到 `localStorage`

#### 3.6 断线重连

- WebSocket 断开后自动尝试重连（指数退避，最多 5 次）
- 重连成功后恢复到同一 PTY 会话（需 Agent 侧会话保活）
- Agent 端 PTY 会话增加 **空闲超时**（默认 30 分钟无输入自动关闭）
- 前端显示重连中状态，重连失败显示「重新连接」按钮

#### 3.7 快捷连接入口

- 主机列表页每行增加「终端」快捷按钮（在线主机直接跳转）
- 支持在新浏览器标签中打开终端（`target="_blank"`）

#### 3.8 右键上下文菜单

- 终端区域右键菜单：复制、粘贴、选择全部、清屏、搜索
- 替代默认浏览器右键菜单

---

### P2 — 可选增强（远期）

#### 3.9 终端分屏

- 单标签内支持水平/垂直分屏（类 tmux）
- 每个分屏独立会话
- 拖拽调整分屏比例

#### 3.10 会话录制与回放

- 后端录制终端输入/输出流（asciinema 格式）
- 管理员可在审计页面回放历史会话
- 录制可选开关（默认关闭，管理员可配置）

#### 3.11 拖拽上传

- 直接将文件从桌面拖入终端区域即可上传到当前工作目录
- 需要 Agent 端获取 Shell 的 `cwd`（通过 `/proc/PID/cwd` 或 `lsof`）

#### 3.12 命令片段

- 工具栏增加「命令片段」按钮
- 预存常用命令（如 `top`、`df -h`、`docker ps`）
- 点击即输入到终端

---

## 4. 技术方案

### 4.1 文件传输架构

```
方案：REST API + Agent HTTP 端点

理由：
1. 文件传输是大体积二进制数据，WebSocket JSON 不适合
2. REST 端点天然支持 multipart upload / stream download
3. Server 已有 Agent 通信能力（通过 AgentWebSocketHandler.sendToAgent）
4. Agent 已有 HTTP 注册机制（httpClient），可扩展 HTTP 端点

实现路径：
- Agent 启动内嵌 HTTP Server（新增，仅监听 localhost 或配置端口）
- Server 调用 Agent HTTP 端点完成文件操作
- 或：通过现有 WebSocket 通道传输文件元数据 + 分块数据（fallback 方案）
```

**推荐方案**：Agent 端新增轻量 HTTP 文件服务，Server 做代理转发。原因：
- 与终端 WebSocket 连接解耦，文件传输不阻塞终端
- 支持 HTTP Range 请求（断点续传）
- 天然支持并发上传/下载

### 4.2 前端架构

```
terminal/
├── index.tsx              # 主页面（标签管理 + 布局）
├── components/
│   ├── TerminalTabs.tsx   # 标签栏组件
│   ├── TerminalInstance.tsx # 单个终端实例（xterm.js）
│   ├── TerminalToolbar.tsx # 工具栏
│   ├── FileManager.tsx    # SFTP 文件管理面板
│   ├── FileTree.tsx       # 文件树组件
│   ├── SearchBar.tsx      # 终端内搜索
│   └── SettingsDrawer.tsx # 设置面板
├── hooks/
│   ├── useTerminal.ts     # 终端实例 Hook
│   └── useFileManager.ts  # 文件操作 Hook
└── types.ts               # 类型定义
```

### 4.3 WebSocket 消息扩展

现有消息类型保持不变，无需扩展（文件操作走 REST）。

### 4.4 Agent 端扩展

```go
agent/internal/
├── fileserver/
│   ├── server.go          // 嵌入式 HTTP 文件服务
│   ├── handler.go         // 文件操作处理器
│   └── middleware.go      // 认证中间件（Token 验证）
├── terminal/
│   └── terminal.go        // 现有，无改动
└── ws/
    └── client.go          // 现有，无改动
```

---

## 5. 安全设计（重点）

### 5.1 威胁模型

| 威胁 | 描述 | 影响等级 |
|------|------|---------|
| **未授权文件访问** | 攻击者绕过鉴权直接访问 Agent 文件端点 | 严重 |
| **路径穿越攻击** | 通过 `../` 访问系统敏感文件（如 `/etc/shadow`） | 严重 |
| **大文件 DoS** | 上传超大文件耗尽磁盘/内存 | 高 |
| **敏感文件泄露** | 下载系统敏感配置文件 | 高 |
| **并发写入冲突** | 多用户同时写同一文件导致数据损坏 | 中 |
| **中间人攻击** | Server-Agent 通信被截获 | 中 |
| **会话劫持** | 终端 WebSocket 会话被第三方接管 | 高 |

### 5.2 安全措施

#### 5.2.1 认证与授权

| 层级 | 措施 |
|------|------|
| **Browser → Server** | 现有 Spring Security 会话认证（JWT/Session Cookie） |
| **Server → Agent** | Agent 注册时生成 **AgentToken**，每次请求携带，Server 验证 |
| **文件操作鉴权** | Server 端校验当前用户是否有该 Agent 的操作权限（基于 RBAC） |

```java
// Server 端伪代码
@PreAuthorize("hasPermission(#agentId, 'AGENT', 'FILE_ACCESS')")
public ResponseEntity<?> listFiles(@PathVariable String agentId, @RequestParam String path) {
    // 转发给 Agent
}
```

#### 5.2.2 路径安全

**Agent 端必须实现：**

```go
// 路径规范化 + 越界检测
func sanitizePath(basePath, requestedPath string) (string, error) {
    // 1. 清理路径
    cleaned := filepath.Clean(requestedPath)
    
    // 2. 解析为绝对路径
    absPath := filepath.Join(basePath, cleaned)
    absPath, err := filepath.Abs(absPath)
    if err != nil {
        return "", err
    }
    
    // 3. 确保在 basePath 内
    if !strings.HasPrefix(absPath, filepath.Clean(basePath)) {
        return "", errors.New("path traversal detected")
    }
    
    return absPath, nil
}
```

**配置项**：
- `EASYSHELL_AGENT_FILE_ROOT`：文件操作根目录（默认 `/`）
- `EASYSHELL_AGENT_FILE_READONLY`：只读模式开关（默认 `false`）

#### 5.2.3 敏感文件保护

**默认黑名单**（Agent 端拒绝访问）：

```go
var sensitivePatterns = []string{
    "/etc/shadow",
    "/etc/passwd",
    "/etc/sudoers",
    "/etc/ssh/ssh_host_*",
    "**/.ssh/id_*",
    "**/.ssh/authorized_keys",
    "**/.gnupg/**",
    "**/.aws/credentials",
    "**/.kube/config",
    "**/.*_history",      // bash_history, zsh_history 等
    "**/.env",
    "**/.env.*",
    "**/secrets.*",
    "**/credentials.*",
}
```

**可配置白名单模式**：
- `EASYSHELL_AGENT_FILE_WHITELIST`：设置后仅允许访问白名单目录

#### 5.2.4 传输安全

| 通道 | 措施 |
|------|------|
| Browser ↔ Server | HTTPS（TLS 1.2+） |
| Server ↔ Agent | Agent 注册时建立 WSS 通道；HTTP 文件请求携带 HMAC 签名 |

**文件请求签名（Server → Agent）**：

```java
// Server 生成签名
String timestamp = String.valueOf(System.currentTimeMillis());
String payload = agentId + ":" + path + ":" + timestamp;
String signature = HmacUtils.hmacSha256Hex(agentSecret, payload);

// 请求头
X-EasyShell-Timestamp: {timestamp}
X-EasyShell-Signature: {signature}
```

```go
// Agent 验证签名
func (m *AuthMiddleware) Verify(r *http.Request) bool {
    timestamp := r.Header.Get("X-EasyShell-Timestamp")
    signature := r.Header.Get("X-EasyShell-Signature")
    
    // 检查时间戳（5 分钟有效期，防重放）
    ts, _ := strconv.ParseInt(timestamp, 10, 64)
    if time.Now().Unix() - ts > 300 {
        return false
    }
    
    // 验证签名
    payload := m.agentID + ":" + r.URL.Query().Get("path") + ":" + timestamp
    expected := hmacSHA256(m.agentSecret, payload)
    return hmac.Equal([]byte(signature), []byte(expected))
}
```

#### 5.2.5 资源限制

| 限制项 | 默认值 | 配置项 |
|--------|--------|--------|
| 单文件上传大小 | 500 MB | `EASYSHELL_FILE_MAX_SIZE` |
| 并发上传数 | 3 | `EASYSHELL_FILE_MAX_CONCURRENT_UPLOADS` |
| 目录列表最大条目 | 1000 | `EASYSHELL_FILE_MAX_LIST_ENTRIES` |
| 下载速率限制 | 无限制 | `EASYSHELL_FILE_DOWNLOAD_RATE_LIMIT` |

#### 5.2.6 审计日志

**所有文件操作必须记录审计日志**：

```json
{
  "timestamp": "2026-03-04T12:00:00Z",
  "user": "admin",
  "agentId": "agent-001",
  "action": "FILE_DOWNLOAD",
  "path": "/var/log/app.log",
  "fileSize": 1048576,
  "clientIp": "192.168.1.100",
  "userAgent": "Mozilla/5.0...",
  "result": "SUCCESS"
}
```

**审计事件类型**：
- `FILE_LIST` — 列出目录
- `FILE_DOWNLOAD` — 下载文件
- `FILE_UPLOAD` — 上传文件
- `FILE_DELETE` — 删除文件/目录
- `FILE_MKDIR` — 创建目录
- `FILE_RENAME` — 重命名

#### 5.2.7 终端会话安全

| 措施 | 描述 |
|------|------|
| **会话隔离** | 每个 WebSocket 连接独立 sessionId，不可跨会话访问 |
| **会话超时** | 无操作 30 分钟自动断开（Agent 端 PTY 关闭） |
| **来源验证** | Server 验证 WebSocket Origin 头，防止 CSRF |
| **会话绑定** | 会话绑定到用户 ID，用户登出时强制断开所有终端会话 |

### 5.3 安全配置清单

```yaml
# Agent 配置示例
easyshell:
  agent:
    file:
      enabled: true                    # 是否启用文件操作（默认 true）
      root: "/"                        # 文件操作根目录
      readonly: false                  # 只读模式
      max-size: 524288000              # 单文件最大 500MB
      blacklist:                       # 黑名单（正则）
        - "^/etc/shadow$"
        - "^/etc/passwd$"
        - ".*\\.ssh/id_.*"
      whitelist: []                    # 白名单（设置后仅允许访问）
    terminal:
      idle-timeout: 1800               # 终端空闲超时（秒）
      max-sessions: 10                 # 单 Agent 最大终端会话数
```

### 5.4 安全测试要求

| 测试类型 | 测试内容 |
|----------|----------|
| **路径穿越** | `../../../etc/shadow`、URL 编码变种（`%2e%2e%2f`） |
| **权限绕过** | 无 Token 访问、过期 Token、伪造签名 |
| **大文件** | 上传 1GB 文件、并发 100 个上传请求 |
| **敏感文件** | 访问黑名单文件、符号链接指向敏感文件 |
| **会话劫持** | 重放旧 WebSocket 消息、跨用户 sessionId |

---

## 6. 里程碑

| 阶段 | 内容 | 预估工作量 |
|------|------|-----------|
| **Phase 1** | 终端工具栏 + 多标签页 + 终端内搜索 + 全屏 | 中 |
| **Phase 2** | SFTP 文件管理器（Agent HTTP 文件服务 + Server 代理 + 前端面板 + 安全加固） | 大 |
| **Phase 3** | 主题设置 + 断线重连 + 快捷连接 + 右键菜单 | 中 |
| **Phase 4** | 分屏 + 会话录制 + 拖拽上传 + 命令片段 | 大（可选） |

---

## 7. 验收标准

### P0 功能验收

- [ ] 可通过文件管理面板浏览远程目录、上传/下载文件
- [ ] 上传 100MB 文件无超时，有进度条
- [ ] 下载文件触发浏览器原生 SaveAs
- [ ] 支持同时打开最多 8 个终端标签
- [ ] 标签切换不丢失已有会话内容
- [ ] Ctrl+Shift+F 打开搜索，可检索滚动缓冲区内容
- [ ] 工具栏按钮全部可用
- [ ] 全屏模式可正常进入/退出
- [ ] 移动端适配不回退

### 安全验收

- [ ] 路径穿越测试全部拦截
- [ ] 黑名单文件访问被拒绝
- [ ] 无 Token/错误 Token 请求返回 401
- [ ] 过期签名（超过 5 分钟）请求被拒绝
- [ ] 超大文件上传被拒绝并返回友好错误提示
- [ ] 所有文件操作有完整审计日志
- [ ] 终端会话超时自动断开

---

## 8. 附录

### 8.1 竞品参考

| 产品 | 文件传输方案 | 终端特性 |
|------|-------------|----------|
| JumpServer | SFTP 侧边栏 + rz/sz | 多标签、会话录制、命令审计 |
| 1Panel | 文件管理面板 | 多标签、分屏 |
| Apache Guacamole | SFTP 虚拟文件系统 | 会话录制 |
| Tabby | 拖拽上传 + SFTP | 分屏、主题 |

### 8.2 xterm.js 插件清单

| 插件 | 用途 | 当前状态 |
|------|------|---------|
| `@xterm/addon-fit` | 自适应尺寸 | ✅ 已使用 |
| `@xterm/addon-web-links` | 可点击链接 | ✅ 已使用 |
| `@xterm/addon-search` | 终端内搜索 | ❌ 待添加 |
| `@xterm/addon-webgl` | WebGL 渲染（性能优化） | ❌ 可选 |
| `@xterm/addon-unicode11` | Unicode 支持 | ❌ 可选 |

### 8.3 相关文档

- [xterm.js 官方文档](https://xtermjs.org/)
- [JumpServer 文件管理设计](https://docs.jumpserver.org/)
- [OWASP 路径穿越防护](https://owasp.org/www-community/attacks/Path_Traversal)
