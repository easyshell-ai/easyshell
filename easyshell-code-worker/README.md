# 独立 code worker（持久服务部署与隔离验收）

## 当前验证状态

专用临时运行时已实际验证 JS/Python 正常计算与 guest 超时、mTLS 跨身份授权、
运行中取消、监督器 SIGKILL 后独立回收、memcg OOM 后正常任务恢复，以及保留原
journal 的重启。产品工作流详情和浏览器代码样例也已实际验证。execution 策略现只
运行 `/opt/code-worker/launcher`，固定 JVM 参数并在 execve 时使用空环境；默认
Worker/CLI 仍拒绝执行。监督器 181 项测试和锁定依赖的离线 strict 构建通过。

以下诊断过程与早期测试数量保留为历史证据，不能把其中“待复验”当作最新状态。
现使用固定官方完整 Temurin JRE21、root 控制的持久发布目录和五个 systemd 服务，
target 整链停止/启动与 restart 后的真实验收通过。preflight 验证受保护且有有效期的
文件哈希/镜像/验收清单，缺失或不匹配拒绝。目标机器仍须独立配置账户、证书及镜像，
物理宿主冷启动与另一条 CI 多阶段构建未在本次执行；详见 `deploy/README.md`。

计时约定更新：Java Context build/显式语言initialize在执行future之前，由监督器固定
15秒startup allowance + guest timeoutMs（最大5000ms，总执行deadline最大20秒）覆盖。
future从用户source eval开始计时，超时context.close(true)。不新增响应phase/初始化消息，
Supervisor内部区分worker返回TIMEOUT和外部TIMEOUT；loop验收只接受前者且源码已交付。
guest权限、资源限额、原Worker与默认CLI拒绝行为不变。该预算已经专用运行时实测。

## 已定位的 scratch 镜像初始化根因与 JDK 完整性

协调者固定无用户源码 probe 已取得明确证据：初始化消息为
`java.lang.InternalError: Error loading java.security file`，类方法栈进入
`java.security.Security.initialize`，由 PythonUtils 初始化触发。复制的本机 Debian/Ubuntu
OpenJDK21 `conf` 含指向 `/etc/java-21-openjdk` 的符号链接，而原 scratch 镜像未包含其目标。
因此当前已确认的阻断是 **JDK 系统配置链接目标缺失**；资源缓存/TruffleAttach 警告并非
这条证据所示的异常根因，先前 passwd/group、locale、timezone 等推测不作为结论。
修复镜像后 Python 容器已经实际复验通过；此段保留根因和打包完整性要求。

若临时复制宿主 JDK 构建镜像，构建方必须审查并包含完整 runtime 文件闭包：

- `conf` 及其全部符号链接目标，特别是安全配置 `conf/security/java.security`；
  可在构建时将已审查的系统 JDK 配置解引用复制进镜像，或保持相同绝对目标路径。
- `lib`、JDK 自带安全/证书及其链接目标、动态加载器和运行所需系统库；不能仅凭 java
  可启动或 `ldd java` 无缺项就认定 Python 所需 JDK 子系统完整。
- 仅复制经审查的发行版运行时配置，不复制宿主整个 `/etc`、用户配置、私钥或应用秘密；
  运行时不 bind mount 宿主配置。UID65532 必须可读，rootfs 保持只读。

长期构建建议使用完整、受维护且 **固定镜像 digest 的 JRE/JDK21 基础镜像**，保留发行版
runtime 配置与库的一致性，再复制 worker dist 和同版本预提取资源；不要依赖未经闭包
验证的宿主 JDK 拷贝。具体 digest 由部署构建方选定并登记，本工具不拉取或猜测镜像。
`Engine.copyResources` 只导出 Graal 语言/engine 资源，不导出 Java runtime、java.security、
系统配置或动态库，成功导出不能证明 JDK 镜像完整。

该镜像修正不要求开放 guest IO/native、网络、环境或进程权限。后续由协调者在空专用
daemon 上重启 fresh journal/reaper 并执行 acceptance，记录真实结果。无需为此修改 Java
机制、重新导出 Graal 资源或再次下载运行包。

## 只读镜像的官方资源预提取方案

新增安全导出入口 `com.easyshell.codeworker.ExportResources <唯一新输出目录>` 与 Gradle
`exportResources`，固定输出 `build/isolated-resources`。本地25.2.4 API 字节码核实签名：
`public static boolean Engine.copyResources(Path, String...) throws IOException`。
该工具固定 js/python，不创建 Context、不运行用户代码；Gradle 以空环境执行。
输出父目录必须已存在且无符号链接，目标必须不存在，原子创建后才调用官方 API；
已有目录（包括任务先前产物）一律失败，不覆盖、不递归删除。失败留下部分产物供检查，
不能当作成功导出。版本标记以 CREATE_NEW 写入，只在官方 API 成功后生成。

新版推荐构建命令：

```bash
./gradlew test installDist exportResources --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  -Dorg.gradle.jvmargs=-Xmx512m
```

镜像复制 `build/isolated-resources` 整体到 `/opt/code-worker/resources`，保持 UID65532
可读、rootfs只读；运行固定 `-Dpolyglot.engine.resourcePath=/opt/code-worker/resources`
与 `-Dtruffle.UseFallbackRuntime=true`。不要把输出目录本身写进 guest 可选参数。
旧 `prepareResources`/`build/graalvm-resources` 保留用于已有诊断，本工具不会覆盖该目录；
后续镜像应以本次成功导出的 isolated-resources 为准。

2026-11-11 实际执行新版推荐命令成功：35 项测试，0 失败/错误/跳过，installDist 与
exportResources 完成。`build/isolated-resources` 约25MiB，dist约172MiB；根盘剩余约7.6GiB，
低于本轮1GiB新增预算。版本标记确认25.2.4/js/python。未创建Context用于导出、未运行
用户代码或容器；测试中固定机制探针与导出操作相互独立。后续重复导出会拒绝已有目录，
不要为重试自动删除资源目录，应由受信构建者明确选择全新目标或审查后处理旧任务产物。

官方 25.2.4 [Managing Internal Resources / Fallback Runtime](https://github.com/oracle/graal/blob/vm-25.2.4/docs/reference-manual/embedding/embed-languages.md)
支持 `Engine.copyResources` 在构建时预提取，运行时以
`-Dpolyglot.engine.resourcePath=/opt/code-worker/resources` 使用只读目录；资源版本必须
与同镜像中的依赖一致，依赖升级后重提取。另有 `polyglot.engine.userResourceCache`
可选写缓存路径，本实现选择预提取，不依赖 `/tmp` 解包。此为可信 JVM 内部资源访问，
不启用 guest IOAccess 或 native access。

构建准备：

```bash
./gradlew installDist prepareResources verifyPreparedResources --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  -Dorg.gradle.jvmargs=-Xmx512m
```

`PrepareResources` 只接受无参数，固定将 js/python 及必要 engine 资源提取到
`build/graalvm-resources`。协调者须把整个目录复制到镜像 `/opt/code-worker/resources`，
UID65532 可读，生产 rootfs 只读；不是宿主 bind mount。dist 生成脚本仍指向拒绝 Worker。

监督器固定 JVM 参数（refusal/execution/固定诊断命令共用）现在为：

```text
-Xmx256m -Duser.home=/tmp -Djava.io.tmpdir=/tmp
-Dtruffle.UseFallbackRuntime=true -Dpolyglot.engine.WarnInterpreterOnly=false
-Dpolyglot.engine.resourcePath=/opt/code-worker/resources
```

不加 `--enable-native-access`，不改变 /tmp noexec 或 Context 权限。诊断主类仍为
`com.easyshell.codeworker.PythonDiagnosticWorker`；新镜像必须包含资源和新版类，旧镜像
不能用来确认该修复。`verifyPreparedResources` 在空进程环境运行同一安全 Context 的固定
1+1，不向 guest 注入对象。没有真实只读容器/非root/tmpfs 验证。

2026-11-11 `test verifyPreparedResources` 离线 JDK21 实测成功：32 个 Java 测试通过、
固定 probe succeeded；资源新增约24MiB；75 项 supervisor fake 测试通过。没有 installDist、
daemon/容器操作。仍有 TruffleAttach 加载 warning（fallback 也可能尝试内部引导），
本机该 warning 未阻止 Python 初始化；未用 ignore 隐藏或 diagnose 输出异常内容。

## 固定 Python 诊断入口

固定无用户源码诊断现增加 `initialization_messages`（全部 cause 合计最多1000 Unicode
字符）与 `class_methods`（合计最多64帧，仅类名#方法，无文件/行号）。仅此独立工具
使用；业务 ExecutionCore/IsolationWorker 仍只返回稳定错误码。消息输出前替换所有非空
进程环境值为 [ENV]，过滤控制字符与Unicode格式字符；固定镜像路径可保留以定位资源。
诊断镜像仍须无秘密环境与输入，不能把脱敏视为对任意秘密完整覆盖的保证。无反射、
无用户源码、无额外权限；不把该功能接入普通任务stderr采集。之前“不输出message”
描述仅适用于旧版诊断/分类函数，新的受信固定入口明确使用上述有限信息。

scratch 镜像诊断进一步增加 USER_LOOKUP、LOCALE、TIMEZONE、RESOURCE、MISSING_LIBRARY
固定谓词；匹配有界异常消息与最多64个公开栈帧，但绝不输出消息、帧、文件或行号。
公开 cause 链为空时不反射 Polyglot 私有原始异常，也不假装 asHostException 对 internal
exception 可用。谓词仍可能 UNCLASSIFIED，匹配不是根因证明。
官方25.2.4 EmulatedPosixSupport.getpwuid 使用 JDK UnixSystem，并取 unix.getUsername 与
user.home；因此合成 UID 映射是合理镜像兼容性实验，但尚未证明初始化失败由它引起。
可由协调者在新镜像中加入固定无秘密 passwd/group 条目：
`easyshell:x:65532:65532:isolated worker:/tmp:/nonexistent` 和 `easyshell:x:65532:`。
时区若加入应为固定 UTC 资产、locale 为固定构建资产，不继承宿主凭据或完整/etc。
这不需要开放 guest IO/native；每次只改变一个可信镜像因素并保留固定诊断参数比较。
不要读取完整诊断消息到工具输出；只收集固定类别，若仍无匹配再审查官方初始化源码。
官方 PythonLocale.initializeFromTruffleEnv 在 InitialLocale 为空时直接使用 Java 默认 locale，
不是必须读取系统 locale 文件的证明；缺系统 locale 目前不能认定为根因。

诊断 stderr 现增加最多8层 `cause_classes`（身份去重，无 message）和固定 `predicates`：
FILE_READ_DENIED、RESOURCE_CACHE、ZIPFS_PROVIDER、USER_HOME、THREAD_DENIED 或 UNCLASSIFIED。
仅固定无用户源码初始化探针可用；每层只检查 message 前8192字符，绝不回显原文。
谓词是诊断线索而非根因证明；链截断可能看不到最底层原因。不存在任意日志开关。
`engine.Compilation=false` 不等价于官方 `truffle.UseFallbackRuntime=true`；真实复验应记录
确切固定参数并包含已预提取的 resourcePath，不因 cache warning 消失就认定缓存问题解决。

执行错误分类修正：合法请求在完成 JSON/字段校验后进入独立 executionBoundary；
Context builder/build、执行线程初始化及 close/cancel 的 Exception/LinkageError 安全映射
EXECUTION_FAILED，不再被外层请求 catch 误标为 INVALID_REQUEST。初始化失败分类测试
使用固定注入异常，不改变 Context 权限或生产请求接口。真实容器根因尚未定位；
guest import json 在原执行 catch 内，不能仅凭 INVALID_REQUEST 断言它失败。
2026-11-11 此分类修正后 JDK21 离线单 worker test 通过 32 项，0 失败/错误/跳过；
未执行 installDist 或真实容器。协调者必须重建分发及镜像才能验证新版分类。

`com.easyshell.codeworker.PythonDiagnosticWorker` 是受信固定诊断主类，不读 stdin、不接受
参数或源码，只按相同安全限制创建 Python Context 并 eval 固定 `1+1`。Java POSIX backend、
HostAccess.NONE、IOAccess.NONE、EnvironmentAccess.NONE、process/thread/native/host class
lookup/loading 全部关闭；没有为资源加载放宽 IO 或 native。仍需专用容器及外部截止。

成功 stdout 为 `status=succeeded, probe=python_literal`；失败 stdout 仅 DIAGNOSTIC_FAILED，
stderr 仅 exception_class、固定 category、固定 stage，不含异常 message/cause/stack/路径。
分类只区分 GUEST_EXCEPTION/HOST_EXCEPTION/INTERNAL_ERROR/LINKAGE_ERROR 等，不能凭此
断言具体库缺失或 IO 根因。新类不接入任何现有 profile/默认启动脚本；协调者重建
installDist/诊断镜像后只能以受信固定命令引用，不能让请求选择主类。当前 adapter 会丢弃
stderr；采集诊断须受信侧只保留该固定 JSON，勿放开普通任务 stderr 日志。

2026-11-11 本机 JDK21 离线 test 通过，新增两个诊断测试验证固定表达式、stdin 不读取、
参数拒绝及分类不含消息；没有容器实测。本机通过不证明 scratch 镜像初始化可用。

## 独立 IsolationWorker 机制候选

新增固定主类 `com.easyshell.codeworker.IsolationWorker`，仅供未来受信外部部署显式
引用。原 `Worker` 及 application 默认主类保持拒绝；监督器 `COMMAND` 仍指向原 Worker，
默认 supervisor CLI 仍关闭。没有 `--enable` 或请求 flag，IsolationWorker 对任何命令行
参数拒绝。它不能自证明隔离，也不能在未验证外部边界时作为普通本机执行器开放。

`ExecutionCore.run` 保留 Python 拒绝；单独 package-private `runIsolated(InputStream)`
只由新入口及机制测试调用，支持 JS/Python `main(input)`。输入通过 guest JSON 解析复制，
不传宿主对象；Python 固定 Java POSIX backend，HostAccess/IO/environment/process/
guest thread/native/host class loading 与 lookup 全关闭。请求/源码/响应字节限制不变，
超时范围 1..5000ms，超时调用 `context.close(true)`，输入必须 EOF 结束。
Python 输出使用 `json.dumps(..., allow_nan=False)`，之后由宿主再次严格解析 JSON。

此实现没有硬内存隔离；JSON 序列化可在字节限额判定前分配内存。Context 创建、stdin
读取及 close 卡住仍须外部截止与整个 cgroup 强杀兜底。生产前必须空 JVM 环境、无秘密
镜像/挂载、固定 digest 与命令、真实资源限制及独立崩溃回收验收。合成环境机制测试
不代替这些要求；不因新入口存在就切换监督器策略。

协调者审查后可重建分发（JDK21，复用缓存）：

```bash
./gradlew installDist --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  -Dorg.gradle.jvmargs=-Xmx512m
```

`installDist` 会包含新类，但生成的默认启动脚本仍运行拒绝 Worker；未来受信 v2 策略
需固定 `/opt/java/openjdk/bin/java -Xmx256m -cp /opt/code-worker/lib/* com.easyshell.codeworker.IsolationWorker`，
不能允许请求选择主类。该 v2 策略本轮未切换，未构建或运行容器。

2026-11-11 JDK21 离线单 worker `test` 实测通过 28 项，0 失败、0 错误、0 跳过：
原核心 4、原边界 5、环境探针 1、新入口机制 18。新用例覆盖 JS/Python JSON 往返、
两个合成环境标记（含 GRAAL_PYTHONHOME）、proc/文件/网络/宿主类/进程/线程/原生尝试、
两种语言无限循环取消、输出限额、NaN、非法 JSON、新入口参数拒绝及 stdout 纯协议。
能力尝试失败不保证失败均来自对应权限检查（例如模块/目标不可用），不证明完整安全。
本轮未执行 installDist；协调者需按上述命令重建后再打包镜像。

新增 [supervisor 生命周期原型](supervisor/README.md)：默认关闭的有界 JSON CLI、
固定策略 Docker adapter、journal/清理/reconcile 模型与纯 fake 测试。没有真实运行、
Start/Attach 或执行启用开关，不改变 Worker/ExecutionCore 的拒绝行为。

独立运行 `./gradlew test`，`./gradlew installDist` 构建。没有服务、部署配置或现有系统接线。
标准输入为一个 UTF-8 JSON 文档，必须以 EOF 结束；标准输出仅有一个 JSON 响应。
入口在读取请求前固定返回 `{"status":"failed","error":"ISOLATION_UNVERIFIED"}`，
不执行代码：进程本身无法证明可信宿主强制其处于不可逃逸的内存受限 cgroup。
没有生产绕过开关，不能将该原型视为安全可运行的 worker。

包内测试直接调用执行核心，仅验证机制，不建立外部隔离保障。核心接收
`language`（`javascript` 或 `python`）、`source`、`input`、`timeoutMs`，代码须定义
`main(input)` 并返回 JSON 数据。输入、输出和源码各有严格字节上限；超时关闭 context。
Polyglot、JS、Python 统一使用官方 25.2.4 候选，Java toolchain 保持 21。
执行核心仍在初始化 Python 之前直接返回 `ISOLATION_UNVERIFIED`，新版机制探针
不构成业务启用依据；不得通过事后清空变量恢复执行。
Graal 执行可能在内存上限之前耗尽进程内存；串行化、原生运行时和取消均需要可信外部
监督器和强制内存限制，不能依赖本进程保证。正式接入前还须满足
`docs/code-worker-security-design.md` 的隔离、监督和对抗验收。

## 新版候选与测试专用探针

23.1.2 的 `posix.environ` 初始化及 Java POSIX 后端直接读取 `System.getenv()`。
官方提交 [c75a34c5](https://github.com/oracle/graalpython/commit/c75a34c5cde2dadf8baf4b85c402263a013e44b4)
将两处改为 Truffle `Env.getEnvironment()`，24.2.0 已包含该修改。24.2.x 及 25.0.5
仍直接读取 `GRAAL_PYTHONHOME`；后续官方提交
[8cfde1ac](https://github.com/oracle/graalpython/commit/8cfde1ac97db329a02942a0ec021e789a872d471)
修正该路径。已核对官方 `vm-25.2.4` 标签包含上述两次修复，无需本地修改第三方源码。
官方该版本嵌入文档支持 JDK 21 fallback runtime；不能据此承诺 guest JIT 性能。

`GraalPythonEnvironmentProbeTest` 仅位于 `src/test`，不进入生产分发；它直接创建
Graal Context，不调用或打开 Worker/ExecutionCore 的拒绝入口。Gradle 测试 JVM
使用替换后的环境，仅设置两个合成变量：普通标记和无效的 `GRAAL_PYTHONHOME`。
测试先在 JVM 内断言这两个值实际存在，再初始化 Python，验证 `os.environ`、
`os.environb`、`posix.environ` 及 `os.getenv` 无法读取它们。
环境字典严格限定为 GraalPy 自行加入的 `UNSAFE_PYO3_SKIP_VERSION_CHECK=1`；
该常量由官方 `PosixModuleBuiltins.postInitialize()` 注入，不代表继承宿主环境。
测试不清空或改写这些字典，也不将“空字典”作为通过条件。

探针固定 Java POSIX backend、`HostAccess.NONE`、`IOAccess.NONE`、
`EnvironmentAccess.NONE`，禁止宿主类查找/加载、进程、guest 线程及 native access；
不传宿主对象，输入输出仅 JSON 字符串且各限 4096 UTF-8 字节。探针还尝试宿主类查找、
读取已存在的构建文件、启动固定 JVM、创建线程及 `ctypes` 原生加载，要求失败。
这些失败仅验证固定操作未成功，不能证明所有同类接口均安全或排除模块不可用的影响。
线程禁用会以宿主 `IllegalStateException` 越过 Python 的 `except Exception`；测试在
Java 侧单独断言该拒绝，避免将其误当成环境探针失败或吞掉其他结果。

可重复命令（单 worker、复用已有 Gradle 缓存）：

```bash
./gradlew test --no-daemon --max-workers=1 \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  -Dorg.gradle.jvmargs=-Xmx512m
```

测试 fork 最多 512MiB Java 堆、串行执行；`@Timeout` 是测试防挂措施，不能替代
可信外部监督器或 cgroup 硬限制。此处仅执行固定可信探针，不接收任意代码。
实际测试结果以 `build/test-results/test` 为准；源码与依赖升级本身不证明测试通过。

2026-11-11 本机 OpenJDK 21.0.13.0.3 验证：上述命令增加 `--offline` 后成功，
共 10 项测试、0 失败、0 错误、0 跳过（核心 4、边界 5、新版探针 1）。
初次解析依赖使用网络，此后复跑离线；25.2.4 Graal 工件缓存约新增 170MiB，
没有复制缓存、构建分发包或创建容器。环境检查确认两个合成值不可见且仅保留
上述内建常量；线程尝试明确收到禁止创建线程的错误。其他能力探针只确认固定
尝试失败，不证明失败均来自对应权限检查。该结果仅证明本机固定机制探针通过，
不证明外部隔离、资源耗尽处理、任意代码安全或可部署性。

## 独立进程方案的限制

启动子进程时构造空环境（而非读取、过滤或清空父进程环境）可以避免把父进程的
环境变量传给子进程，但不能证明 GraalPython 不会通过其他宿主接口读取信息；当前
Python 拒绝逻辑因此仍须保留。JVM 的 `-Xmx` 不限制原生分配，也不能代替 cgroup
内存及 swap 硬限制。任务进程自行检查自己的 cgroup 或由同一进程自行计时，都不能
证明限制受可信方强制，亦不能保证内存耗尽或取消卡住时完成清理。

可行的后续方向是由可信的容器外编排器先创建带内存、swap、CPU 和 PID 限制的
一次性任务容器，再以明确构造的空环境启动 worker；独立监督器以单调时钟计时，
超时先请求 `context.close(true)`，宽限期后终止整个任务 cgroup，并确认其为空。
监督器自身崩溃或重启时还需由编排器回收孤儿。启动、限制验证、监督和回收任一
失败都必须拒绝任务；这些能力目前均未实现或验证，不能用本机子进程降级替代。

当前检查环境存在 cgroup v2 且挂载显示可写，但未证明任务专属的可信委派、实际
硬限制或崩溃回收；这不是启用依据。正式验收还需要在目标部署环境直接验证限制
生效、超时强杀及监督器重启回收，和设计文档中的其他隔离与对抗测试。
