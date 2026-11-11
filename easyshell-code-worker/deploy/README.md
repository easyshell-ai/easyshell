# Linux 隔离 code 部署与验收

本目录提供固定 native launcher、严格依赖构建、受保护发布清单校验及五服务模板。
2026-11-12 在专用测试运行时完成五服务启动、整链停止再启动和完整 mTLS 验收；
固定官方 Temurin JRE21镜像同时通过 OOM、SIGKILL回收及取消。静态检查不替代真实验收。

## 失败关闭的部署准入

`supervisor/control.py` 的 execution Cmd 现固定为 `[/opt/code-worker/launcher]`，
严格 Inspect 校验拒绝直接执行 JVM 或附加参数。launcher 在 JVM 启动前以空 envp 调用
固定绝对路径及主类；refusal/resourceprobe 保留各自固定的受信验收命令。请求不能选择
入口。此改动不代表以下生产模板、固定 digest 构建和主机重启流程已经验收。

execution 与受信 resourceprobe 容器的 Inspect 环境严格限定为一个公开固定 PATH，不能包含任何其他环境项；
这是兼容 Docker scratch 构建自动 PATH 的显式策略。它不是 JVM 环境：静态 launcher
在启动 JVM 前用空 envp 替换全部继承环境，合成秘密及额外参数的 chroot 探针已验证。
refusal 继续要求空容器环境。resourceprobe 只运行固定可信内存诊断，不接受用户源码；
JVM 空环境承诺适用于 execution 的 native launcher，不把诊断入口当作业务执行入口。

reaper 首次启动仍要求新 journal；可信重启可显式使用 `--resume-existing`，要求原文件
为当前用户所有的 0600 普通文件、路径无符号链接，并持有独占文件锁。恢复只对账和
清理原记录，不重放执行。该入口已有保留记录、独占和错误路径测试，但跨主机重启、
镜像策略变化及真实故障恢复仍需部署验收。不要删除或改名旧 DB 绕过限制，不要用新
journal 遗忘旧资源。无法对账的记录仍阻止启动，不能以新 journal 追认旧任务已清理。

`service-entry.py` 对 reaper 的既有受保护 journal 自动选择保留恢复，对 orchestrator
从同一 journal 读取 session，并核验独立 reaper 的 PID/starttime 与近期心跳；拒绝静态
`--reaper-session` 覆盖。此启动交接已有单测，但 systemd `After=` 不表示应用 ready，
reaper 模板现使用 `Type=notify`，仅在首次对账成功、心跳已提交后发送 `READY=1`；
失败不通知。Unix datagram 与失败路径已有测试，但真实 systemd readiness/restart
整套模板尚未验收，不能据此解除阻断。专用临时 `systemd-run` 单元已实际验证真实 reaper
的 READY、保留 journal 重启、新 session 和原任务记录不重放；这不覆盖完整模板的
文件系统沙箱、依赖服务联动、开机启动、固定镜像构建或证书配置。

`launcher.c` 是静态程序，拒绝所有额外参数，以固定绝对 JVM 路径、固定 JVM 参数及主类
调用 `execve`，envp 只有 NULL。不得替换为 shell、Java 包装器或继承环境的 exec。
scratch 最终层仅复制受信 JRE 基础镜像的完整文件系统，避免继承该镜像的 ENV/ENTRYPOINT/
VOLUME 配置；它仍需审查文件内容、原生库、JVM 版本和许可证。必须选择干净的完整 Linux
JRE21 镜像，JVM 已位于上述路径，不能从本机 JDK 拼装。JRE 全树复制也可能带入无关工具，
必须对批准镜像做内容/SBOM/秘密审查，不允许把“来自受信仓库”视为无需检查。

所有 unit 的 `ExecStartPre` 调用 `preflight.sh`，要求 `/etc/easyshell-code/release.json`
为 root 控制、无符号链接、不可被其他用户修改的清单。清单绑定发布目录的脚本SHA256、
任务镜像配置ID、预登记daemon ID、已执行验收类别及最多31天有效期；缺失、篡改或过期
返回78。清单是受信部署者的验收声明，不自动发现或创建信任；执行时另核daemon/真实
cgroup/reaper/mTLS。更换任何文件或镜像后必须重新验收，禁止仅补哈希绕过失败。

运行时 root 服务需要设置UID和共享挂载视图：containerd私有网络，dockerd通过固定
`runtime-entry.py`核验containerd MainPID/root executable/config，持namespace文件描述符
进入同一mount/net namespace。两runtime使用0022 umask且不继承NoNewPrivileges，以免
runc无法设置UID或overlay根不可遍历；这两个进程本身属于可信特权编排设施。
上层reaper/orchestrator/control仍NoNewPrivileges，control无capabilities；任务仍非root、
capdropALL、readonlyroot、none网络及no-new-privileges。不能把runtime例外扩散到任务。

`tmpfiles.conf`重建重启后易失的专属运行目录；`easyshell-code.target`拉起依赖链。
仅在完成账户/证书/清单/配置和镜像预加载后安装并启用target。该次实际验证是整链
stop/start，不重启承载其他服务的宿主；不能把它称作物理主机冷启动实测。

## 构建输入与实际验收路径

本次使用官方清单解析的 Linux amd64 Temurin JRE21
`eclipse-temurin@sha256:d4cbe963bb4f3a769d56473b32db2bfb46b0d4b29797c152815da692d9f9aab5`，
将完整 JRE 文件系统、严格锁定构建的 worker dist、预提取资源和静态 launcher 组合为任务
镜像，并按不可变 image config ID 验收。没有用宿主 JDK 拼装此最终镜像。
下面的全容器多阶段构建是另一种受信 CI 路径；JDK/CC digest 必须独立配置，不能声称
本次已经运行该多阶段 Dockerfile。两条路径交付同一固定入口/权限契约。

构建上下文是 **干净 worker 源码目录**。Dockerfile 按文件复制源码，不复制本机 build、
Gradle 缓存、证书或整工作区。为架构/OS 固定一个发布矩阵；三个镜像的 digest、平台、
工具链版本和来源证明都记录到发布清单。digest 必须由用户/部署维护者提供，没有示例假值。

构建前独立批准并提供：

| 输入 | 要求 |
| --- | --- |
| `CODE_JRE_IMAGE` / `CODE_JRE_REPOSITORY` | 完整批准仓库 `@sha256:` + 64 个小写 hex；Linux JRE21 全根目录 |
| `CODE_JDK_IMAGE` / `CODE_JDK_REPOSITORY` | 同上，JDK21，含 shell、sed、head 等构建工具 |
| `CODE_CC_IMAGE` / `CODE_CC_REPOSITORY` | 同上，预装 gcc、静态 libc、readelf、grep；无需现场安装编译器 |
| `GRADLE_DISTRIBUTION_SHA256` | 官方分发经可信渠道复核的 Gradle 8.13 zip SHA256 |
| `CODE_OUTPUT_TAG` | 显式本地构建标签；上线必须另登记真实镜像 digest/config ID |

`build-image.sh` 在调用 Docker **之前**强制校验仓库匹配及 digest 格式。仓库批准值由独立
受保护 CI 配置提供，不能从待检查镜像自动派生。Docker ARG 不支持 shell 的 `${VAR:?}`；
Dockerfile 使用无默认值的 ARG，禁止绕开 helper 直接以未验证参数构建。不要向 ARG 注入秘密。
gcc builder 是额外供应链依赖，成本较高；本方案不现场装大工具链、不假定 JRE 有 gcc。

仓库已提供候选 `gradle.lockfile` 和 `gradle/verification-metadata.xml`，并通过 JDK21
离线 strict 校验构建；这些哈希由当前制品生成，发布前仍须独立复核，不能作为来源证明。
使用 `deploy/locking.init.gradle`，在同一 JDK21/平台解析 `test installDist
exportResources` 所需的所有配置，写锁并审查。polyglot、js-community、python-community 必须
全部严格为 **25.2.4**，禁止 `+`/SNAPSHOT/changing；也锁定 Jackson、JUnit 和全部传递依赖。
checksum 元数据须覆盖所有下载制品、POM/模块元数据；不得把首次下载结果自动视为受信。
2026-11-11 对候选元数据的 70 项制品做了 Maven Central 外部对照：60 项匹配公开
SHA-256，10 项在未取得摘要文件后受限下载公开制品并重新计算 SHA-256，全部一致。
这不是 PGP 签名验证、漏洞审计或基础镜像供应链证明，更新版本后须重新核验。
wrapper JAR、Gradle zip 和锁文件本身也纳入发布清单。更新元数据须在独立候选中审查，
不要为消除校验错误而自动接受新哈希。

批准后由 CI 显式运行 `bash deploy/build-image.sh`。构建允许正常 Maven/Gradle 依赖下载，
使用 strict dependency verification 和 strict locking，缺少锁或 checksum 即失败。
`exportResources` 调用现有 `Engine.copyResources` 导出 JS/Python 25.2.4 到新目录，再复制到
`/opt/code-worker/resources`，去除写位。运行时还必须 readonly root；不允许首次启动联网下载
资源或导出到可写 cache。Docker 构建必须从干净源码开始，避免已有 export 目录影响结果。

发布时对同架构/相同批准输入做两次独立干净构建，对比 launcher、JAR、依赖、资源树及 JRE
文件 SHA256。记录 BuildKit/编译器/Gradle/平台与时间规范化策略；未对比前只可称“可重复的
构建流程”，不能声称镜像字节完全可复现。记录真实镜像配置 Env/Entrypoint/Cmd/Volumes/
User，确认没有默认 ENV、秘密、卷及非预期启动元数据；启动后再次严格 Inspect。

## 离线 Linux 源码/依赖/输出核验

`verify-linux-source.sh` 不下载、不解压、不构建、不安装。由受信发布清单提供源 tar SHA256
及 manifest SHA256，二者不能从同一次未认证下载中自行接受。manifest 每行是 GNU sha256
形式 `64hex`、两个空格、相对路径，路径前缀分别为 `source/`、`deps/`、`outputs/`。
三组都必须非空，列举对应目录的 **全部普通文件**，不允许额外文件、符号链接或特殊文件。
源 tar 没有顶层包装目录，文件路径必须与 `source/` 后的路径一致；拒绝链接、设备和穿越。

```bash
bash deploy/verify-linux-source.sh \
  "$SOURCE_TAR" "$SOURCE_TAR_SHA256" "$SOURCE_TREE" \
  "$DEPENDENCY_TREE" "$OUTPUT_TREE" "$TRUSTED_MANIFEST" "$MANIFEST_SHA256"
```

仅检查受信且已限大小的发布制品；不是任意互联网压缩包解码沙箱。deps 应包含全部批准
Gradle 制品、wrapper 和元数据，outputs 包含 launcher/JAR/资源等实际交付文件。该核验
证明与批准哈希相同，不能替代签名/来源认证或证明源码能生成这些输出。

## 专用 runtime 与 systemd 模板

五个 unit 分别隔离 containerd、dockerd、reaper、orchestrator、control。
`containerd.toml.example` 和 `daemon.json.example` 只能作为与所锁 runtime 版本核对后的配置
起点。专用 daemon 必须具有独立 data-root、exec-root、containerd socket 和 namespace，
不能接默认 Docker 或与其他工作负载共享。不提供 TCP runtime socket、不开放 docker 组。
模板保留真实宿主 PID/mount namespace 供 HostGuard 检查 `/proc` 和 cgroup v2；不能随意加入
PrivateUsers/ProtectProc/ProcSubset/私有 mount/cgroup namespace，使其看到伪造或不完整信息。
root daemon 仍具有宿主级权限，专用路径不等于强主机隔离；高风险工作负载应在专用 VM/主机。

部署维护者预先安装、核验并锁定 Linux/Python/runtime 二进制；模板不安装用户、目录或服务。
路径使用固定发布前缀 `/usr/lib/easyshell-code`，全部源码/脚本/root-owned，不可被 UID17001 改写。
所有祖先目录不可被不受信用户替换，无 symlink。按以下边界手工受保护地 provision：

| 位置 | 所有者/权限与用途 |
| --- | --- |
| `/etc/easyshell-code` | root 控制，配置 0600；禁止 guest/server 写入 |
| `/run/easyshell-code/runtime` | root:root 0700，runtime sockets 0600 |
| `/run/easyshell-code/control-rpc` | UID/GID17001 0700；socket 17001:17001 0600；root orchestrator bind/chown |
| `/var/lib/easyshell-code/{execution,orchestrator}` | root:root 0700，SQLite 0600 |
| `/var/lib/easyshell-code/control` | 17001:17001 0700，SQLite 0600 |
| `/etc/easyshell-code/tls` | 17001:17001 0700，cert/key/CA 均该 UID 0600，无 symlink |

控制用户固定 UID/GID17001，专属账户，不加入 docker/root/runtime 组，不与其他服务共用，
账户数据库不得授予补充组；`SupplementaryGroups=` 本身不能取消账户数据库已有成员资格，
因此需真实检查 `/proc/<control-pid>/status` 的 Groups/CapEff 及 sockets 访问拒绝。
control unit 将 runtime 路径设为不可访问，无 capabilities，只读 RPC 目录，只能写自身 DB。
orchestrator root 的能力限于所需目录穿越/chown，reaper 不持 capability；真实内核权限若
不满足应失败并评审，不能直接扩大权限。daemon 的 Delegate/挂载/LSM 需按目标发行版验证，
`NoNewPrivileges` 与 `ProtectSystem=strict` 不能证明所有 runtime 功能已通过。

## 证书、启动交接、恢复与验收

1. 通过受信 PKI provision 服务端证书/私钥及 client CA；服务端 SAN 匹配部署地址，客户端
   证书唯一 DNS SAN 精确命中 allowlist。`example.invalid` 只是占位身份。不得禁用 CA/主机名
   验证，不把私钥写到 Environment、CLI 内容、镜像或示例。证书路径可出现在参数，私钥内容不可。
   轮换采用受保护原子发布并重新检查 owner/mode，再受控重启；不提供自签自动安装脚本。
2. 核心 launcher 集成通过后，生产 preflight 必须校验 root-owned 完整配置、路径无 symlink、
   二进制/镜像哈希、内核 cgroup v2/systemd、AppArmor、builtin seccomp、挂载及 socket ACL，
   对照可信登记 daemon ID，精确核对 journal ownership 和 pending 任务；任何未知均拒绝 ready。
   daemon ID 不能刚从任意 socket 读取就当可信。核验仅由受信 orchestrator/reaper 访问 runtime。
3. 顺序为 containerd → dockerd → 独立 reaper。`After=` 仅是启动顺序，不代表 ready。
    reaper 的 `Type=notify` 在对账后通知 ready，受保护 `service-entry.py` 从 journal 读取并验证
    活跃 session；unit 不解析日志、不渲染或复用静态 session。orchestrator RPC 验证成功后
    再启动 UID17001 control；完整依赖服务 readiness 仍须目标机器验收。
   `service-entry.py` 用 Python `-I -B` 和固定绝对发布路径，避免 cwd/PYTHONPATH 导入。
4. 默认 `Restart=no`，每 300 秒最多 3 次启动。reaper 退出即停止依赖 orchestrator；失效期间
   readiness 必须拒绝。不要用无界重启、删 DB、自动 unlink RPC socket 处理故障。
   每次重启重新确认旧进程身份、session、pending、精确 ownership 与 cgroup 清空；残留 socket
   只有确认无对应活进程后才允许受信维护者处理。未知状态保留证据，不重派同一任务。
5. SQLite execution/control/RPC metadata 各自持久化，连同 WAL/SHM 作为一致性集合保护，
   不放 `/tmp` 或 ephemeral RuntimeDirectory，不默认清理卷、缓存、历史 journal。备份采用
   SQLite 一致性快照/受控停写，恢复后不自动执行历史任务。结果仅内存 TTL，重启不可恢复
   明文结果；unknown 不表示未执行。业务重试不得换 ID 绕过不确定状态。
6. 真实验收包含 JS/Python normal、worker 来源 TIMEOUT、严格空环境（含恶意 JAVA_TOOL_OPTIONS/
   LD_PRELOAD 注入拒绝）、只读资源、无网络/挂载/秘密、cgroup 实际限额和清空；独立 reaper
   在 supervisor SIGKILL 后精确回收；取消只有 cleanupVerified receipt 才算完成，pending/
   网络错误/普通 terminal 均不算取消成功。取消与正常完成竞态保持已验证原终态。
7. 通过 mTLS 验证无证书/错误身份拒绝、跨 owner GET/DELETE/重复 POST 403、幂等冲突、重启
   unknown 不重派；control UID 不能读取 runtime socket，server/guest 均不持 runtime 权限。
   health 会真实创建 probe 容器，只有授权实际验收时调用。日志不记录请求/源码/输入/输出/
   私钥，模板关闭普通服务 stdout/stderr；reaper 仅保留固定 ready/session 元数据。

离线检查：

```bash
bash deploy/static-verify.sh
```

该命令执行 bash 语法、配置/模板不变量、Python 语法和可用时 C 语法检查，不调用 Docker、
runtime、systemctl 或网络，也不读取真实证书。缺失/篡改/过期清单拒绝由独立单测验证。

当前专用测试部署已经安装五个持久 systemd 单元与 target，整链 stop/start 后真实六组
mTLS验收通过，目录由 tmpfiles 重建。未重启承载其他服务的整台宿主机；冷启动前需核对
镜像仍预加载、runtime配置路径、账户、证书有效期、release清单有效期与所有临时目录。
默认清单最多31天且不会自动续签。更新必须关联重新验收，不能设置永久自信任。
