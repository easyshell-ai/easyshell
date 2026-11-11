# 默认关闭的外部生命周期监督原型

## 固定 memcg OOM 验收（未真实运行）

`resource_acceptance` 仅支持固定resourceprobe，命令为MemoryLimitProbe，独立名称前缀
与协议标签。请求不能选择它；RPC仍显式execution，默认CLI仍拒绝。Java探针不eval任何
payload，先等待有界stdin EOF，再最多保留128个8MiB DirectByteBuffer并逐页写入。
固定-Xmx256m和-XX:MaxDirectMemorySize=1g使直接内存预算高于512MiB容器限额；不增加
容器memory/swap/CPU/PID等预算，不在宿主运行该类，也不在机制test执行内存耗尽。

先重建dist，把新增MemoryLimitProbe类的JAR替换进此前完整JDK+resources镜像，取得新
image config ID；依赖/资源未变化，无需重导出或新下载整镜像：

```bash
./gradlew test installDist --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  -Dorg.gradle.jvmargs=-Xmx512m
```

在空专用daemon上启动fresh journal的reaper，额外显式加 `--resource-probes`：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.reaper \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<新固定镜像ID>' --journal '<私有目录>/resource-new.sqlite' --resource-probes
```

该受信启动选项只允许顺序resourceprobe/execution记录，拒绝混合并发profiles；普通reaper
默认不跨profile认领。保持它独立运行后，用ready session执行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.resource_acceptance \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<同一镜像ID>' --journal '<同一journal路径>/resource-new.sqlite' \
  --reaper-session '<ready session>'
```

最多同时一个任务，执行期限不超过20秒且保留reaper兜底。必须实际HostGuard验证限额
并保存身份后才交付EOF使探针分配；只接受Supervisor从精确owned Inspect OOMKilled=true
得出的RESOURCE_EXHAUSTED，加持久cleanupVerified凭据与空daemon。Java堆OOM、DirectMemory
上限异常、EXECUTION_FAILED、空stdout、TIMEOUT均不作为内核OOM证明。之后固定正常JS
必须返回n=4并清理验证成功，才报告memcg_oom_and_js_recovery。失败停止，不盲重试。
若探针意外正常完成或内核未标OOMKilled，只能失败诊断，不放宽判定。

PID/CPU本轮仅保留HostGuard对真实pids.max/cpu.max的读取证明，没有线程/fork膨胀触发
验收，不声称证明PID限额触发行为。未修改控制面/RPC；资源验收不是普通任务执行接口。
本轮相关11模块fake通过101项，0失败/错误/跳过；没有daemon或OOM实测。

## 固定 mTLS HTTP 验收客户端

取消失败现在附带固定 `stage` 与最多12项 `diagnostics`：阶段为await_running/delete/
poll_terminal/post_result，每项仅包含httpStatus、白名单status/errorCode和transport。
未知字符串替换为unrecognized，异常只标transport=unverified；不输出raw body、output、
requestId、源码、证书或异常文本。DELETE/轮询先失败时仍附上有界POST结果摘要，便于定位
旧snapshot响应或core claim前取消竞态，但本工具不据此推断具体原因或伪造取消通过。
GET running仅证明control元数据状态，不证明core claim、HostGuard验证或guest开始执行。
不增加固定sleep来冒充上述证据。默认ID前缀已缩短为accept-，固定请求构造校验ID≤64字符。

复验仍使用下方同一CLI参数，只需部署新的control_acceptance客户端脚本；本轮未改
control_server/orchestrator服务实现，也未更新或触碰运行中的live snapshot。

`control_acceptance.py` 只通过控制面执行固定无破坏场景，不导入DockerAdapter、不访问
runtime socket、不读容器清单。协调者已报告实际部署的health/正常JS/Python/mTLS拒绝及
UID权限隔离通过；本工具开发阶段未连接该环境，也未读取真实证书。

从worker目录显式运行（自行提供本机可信证书路径；本示例没有被执行）：

```bash
python3 -B -m supervisor.control_acceptance \
  --endpoint https://localhost:19443 \
  --cacert '<内部CA文件>' \
  --clientcert '<已授权客户端证书>' \
  --key '<对应客户端私钥>'
```

endpoint必须恰为HTTPS origin，无路径、尾斜线、query、fragment或userinfo；主机名必须
匹配服务端证书SAN。证书仅由SSLContext读取，不打印其内容/路径或原异常；不禁用CA和
hostname验证。每HTTP请求绝对期限≤25秒，health≤10秒；只发送定长JSON，完整请求≤60KiB，
完整响应≤65536字节，UTF-8 JSON解析限深度/元素数。每次HTTP操作只尝试一次，网络或TLS
异常一律失败，不当作拒绝证明或取消成功。stdout仅固定case/status/error及证据范围，退出码
0表示本次固定案例通过，1表示未验证。

固定案例包括health精确ready/policyVerified=true；JS/Python正常output={n:4}；GET返回
完整相同结果；同ID同内容重复POST结果一致；同ID改input必须409；两种语言无限循环必须
200 failed/TIMEOUT。每次运行随机ID前缀，不跨失败自动重派。重复POST是幂等契约检查，
工具没有Docker权限，不能独立观察“没有第二个容器”；报告containerCount=not_observed。
同样，TIMEOUT无法从HTTP分辨worker/external来源，worker TIMEOUT来源需可信backend验收
证据支持，不能由本脚本单独证明。清理证据标为backend_contract。

取消案例在线程中POST固定JS循环，GET观察running后DELETE同ID；DELETE仅接受
202/cancel_pending或200/cancelled，最多5秒（含DELETE）轮询终态。只有GET确认cancelled
且POST正常返回cancelled或failed/CANCELLED才通过；一直pending报告
CANCEL_PENDING_UNVERIFIED并失败。正常完成、TIMEOUT、不确定码、网络/TLS错误均不能替代
已验证取消。等待POST线程收尾仍受其25秒HTTP期限限制，不会扩大5秒终态轮询预算。

可选第二个不同且同样获准的mTLS服务身份：

```bash
# 在上述参数后追加：
  --other-clientcert '<另一DNS身份客户端证书>' --other-key '<另一私钥>'
```

提供后先要求另一身份health成功，再检查GET/DELETE/重复POST访问原资源均403；未提供则crossOwner=not_checked，
不影响其余案例。相同DNS身份的另一张证书不能代表跨owner测试。第二身份必须已在allowlist，
否则第二身份health失败，整个验收失败，不能将身份拒绝误当作资源归属证明。

fake回归无需网络或凭据：

```bash
python3 -B -m unittest supervisor.test_control_acceptance -v
```

## Review 修订后的当前契约（优先于下方阶段记录）

公开路径仅POST `/internal/v1/code-executions`、GET/DELETE
`/internal/v1/code-executions/{id}`、GET `/internal/v1/code-health`。
POST同步等待，受理后交付/清理未知、等待超时、重启后结果不可用均返回
`503 {requestId,status:"failed",errorCode:"CODE_EXECUTION_UNCERTAIN"}`；这不是确定执行失败，
不得盲重试或更换ID重派。受理前非法输入/无配置等仍使用普通400/413/503。
GET有缓存返回与POST相同的完整终态；结果过期或unknown返回同一不确定码，running/queued
返回状态。取消意图pending返回202/cancel_pending，不覆盖running，不丢弃后续执行结果。

RPC cancel先核验metadata owner/fingerprint，通过独立core Journal连接调用
`request_cancel(id,fingerprint,session)`，最多等待3秒的 `wait_cancel` verified receipt。
CLI使用 `supervisor.cancel.open_journal(trusted_existing_path)`，每次取消新连接并关闭，
不共享执行线程SQLite连接；执行返回也核对 `cancel_receipt`，未验证不得当作已清理。
只有cleanupVerified=true且terminalReason=cancelled才确认取消；normaldone的metadata终态
以及normal cleanup receipt都不转换成cancelled。receipt API不存在/失败均pending。
claim与取消意图共享metadata锁，claim前意图阻止新claim；claim后lease交接再次递交意图，
不把claim不存在当作清理证明。runtime收尾仍由core/reaper/CAS控制，本模块不改它们。

请求及worker结果各≤60KiB，RPC完整帧≤128KiB，HTTP完整JSON≤65536 UTF-8字节。
HTTP与RPC使用ensure_ascii=False，包装后再次检查；超限固定OUTPUT_TOO_LARGE，无结果原文。
只有清理确认的terminal记录在固定24小时replay window后回收，unknown/pending不回收。
窗口时间戳持久保存，重启不能提前忘记ID；内存结果仍60秒TTL，重启不能恢复明文结果。
24小时之后原ID允许重新受理，因此调用方仍须使用唯一ID，不得将回收窗口当自动重试机制。
RPC回收只针对自身metadata精确关联的terminal core行及相关lease/guard/control记录，
不扫描前缀、不处理其他任务；journal失配或残留pending失败关闭。时钟回拨延迟回收。

health独立10秒预算，user整连接25秒预算。health每次检查runtime identity/reaper，
60秒缓存过期时当次刷新固定probe；并发请求共享probe future，随后仍检查存活身份。
固定probe采用内部保留ID，清理验证后精确退休其core记录，避免耗尽用户journal容量；
失败/清理未知的probe不回收。adapter调用/lease由RPC deadline facade封顶，未改core。
本轮只运行fake测试，不代表真实10秒probe或部署隔离验收通过。

生产身份固定为专属control UID17001、orchestrator UID0；root创建RPC目录后chown17001、
mode0700，orchestrator bind后socket chown17001/mode0600。服务端SO_PEERCRED期望17001，
客户端期望0；TLS cert/key/CA归17001且0600。HTTP CLI拒绝root及相同双方UID，
不可借root control绕过runtime权限。下方CLI参数保持有效，由协调者使用runuser启动。

完整fake回归命令：

```bash
python3 -B -m unittest discover -s supervisor -t . -p 'test*.py' -q
```

## 本地可信取消 API（core，不含 RPC owner 授权）

RPC代理必须先经mTLS/peerUID及metadata owner/fingerprint校验，再调用以下API。
不要在线程间共享Supervisor/Adapter/Journal.db；每个cancel handler自行打开并关闭独立
连接，无Docker凭据或runtime调用：

```python
from supervisor.cancel import open_journal
journal = open_journal(trusted_existing_absolute_journal_path)
try:
    receipt = journal.request_cancel(request_id, expected_fingerprint, expected_reaper_session)
    if not receipt['cleanupVerified']:
        receipt = journal.wait_cancel(request_id, expected_fingerprint, expected_reaper_session, timeout=3.0)
finally:
    journal.close()
```

三个方法准确签名：
`Journal.request_cancel(request_id, fingerprint, session)`、
`Journal.cancel_receipt(request_id, fingerprint, session)`、
`Journal.wait_cancel(request_id, fingerprint, session, timeout=3.0)`。
返回均为 `requestId/cancelRequested/cleanupVerified/terminalReason/state`，不返回容器ID、
nonce、路径或内容。state为terminal/cancelling/unverified；terminalReason仅凭据验证后
返回 succeeded/failed/expired/cancelled，否则null。未知claim抛Rejected(CANCEL_NOT_FOUND)，
fingerprint/profile/session/nonce不匹配抛CANCEL_CLAIM_MISMATCH。必须传可信已登记session，
不接受从请求任取容器ID、nonce或路径。wait取0..3秒，不保证3秒内必定完成；SQLite每次
锁等待上限50ms，超期/锁冲突均不可假报true。RPC整体预算须覆盖wait+传输，1秒DELETE
只能选择短wait后返回pending，不应无限等待或重试执行。

core无claim时不能确认取消：orchestrator须先在自己的metadata持久写意图并阻止create，
等claim可用再绑定core。core只校验当前execution record及lease session，短事务绑定
task_controls的nonce/profile/session并写flag，不自行执行owner权限判断。

清理权通过task_controls三秒CAS租约及随机token仲裁，Docker清理预算两秒；事务不跨
DockerIO。adapter在kill/remove前再次检查token及有效期，旧领取者不能提交终态；
未知或已过期token拒绝。reaper立即处理已有saved guard的取消任务，不等待任务20秒
deadline；Supervisor在create/start/交付前检查flag。Supervisor与reaper使用独立adapter。
清理成功后短事务同时写terminal和凭据（containerid、nonce/profile/session、guard
path/device/inode、cgroupempty）；guard identity保存后不可覆盖。cancel API只读该凭据，
绝不把普通task terminal、metadata failed、404或取消flag当作清理证据。

完成竞态以清理提交事务为界：取消flag先提交则最终cancelled；成功清理凭据先提交则
后续cancel幂等保留succeeded等原terminalReason，不伪造取消生效。reaper已验证取消后，
Supervisor即使Attach异常/404也返回稳定CANCELLED，不返回成功或覆盖已验证terminal。
清理权被占用时Supervisor最多等三秒匹配凭据，未确认仍失败关闭。早于guard保存的取消
不假造cgroup证明，可能保留uncertain；没有启动的任务也不凭空产生cleanupVerified。

本轮不修改orchestrator_rpc/control_server，由RPC代理接线并决定公开错误映射。
默认CLI、guest权限、固定策略不变；无daemon实测。新增test_cancel覆盖独立连接、
claim绑定、flag非证明、CAS/过期接管、正常完成竞态、无guard、阻塞Attach后取消及
reaper在租约到期前回收。
本阶段上述core相关九模块回归通过92项，0失败/错误/跳过。整目录discover曾包含并行
代理正在修改的RPC/控制面测试并失败，本轮不宣称那些集成用例已绿；需代理完成接线后
统一复验。没有连接daemon或执行真实取消，Java dist无需重建。

## Unix RPC orchestrator 与同步 HTTP 契约

`orchestrator_rpc.py` 新增受信进程及 `UnixRPCBackend`。仅 CLI factory 延迟导入
DockerAdapter/HostGuard/reaper；control 进程导入客户端不会导入这些模块或获得 runtime
socket。默认 control backend 仍未配置。明确绑定示例（由受信部署代码执行）：

```python
service = ControlService.with_unix_rpc(
    "/private-control/control.sqlite", {"server.internal"},
    rpc_socket="/run/easyshell-code/control-rpc/orchestrator.sock",
    control_uid=17001, orchestrator_uid=0,
)
# ControlHTTPServer(address, service, TLSConfig(cert, key, ca, frozenset({"server.internal"})))
# TLS cert/key/CA、地址及UID均来自受信配置，不从请求或guest读取。
```

HTTP POST `/internal/v1/code-executions` 同步有界等待，最多24秒等待结果，整连接绝对期限默认25秒
（包括TLS/header/body）。成功200为 `{requestId,status:"succeeded",output:...}`；执行失败
200为 `{requestId,status:"failed",errorCode:"TIMEOUT"}` 等固定码。未知/交付不确定/清理
不确定503为failed/RUNTIME_UNAVAILABLE，不泄露原异常。RESPONSE_TOO_LARGE映射
OUTPUT_TOO_LARGE。鉴权、冲突、大小、限流保留403/409/413/429等HTTP状态。
GET `/internal/v1/code-executions/{id}` 200仅返回requestId/status；DELETE同路径，只有backend确认清理才200
cancelled，未验证503，不返回202。同步重复POST复用原记录和TTL内结果，不再次执行。
结果过期或重启unknown时返回503，不自动重试/换ID。敏感结果仍须由Java端唯一的
SensitiveDataFilter在对用户输出、日志或落盘前处理，控制面不新增掩码引擎。

GET `/internal/v1/code-health` 同样要求mTLS身份，且仅在backend精确返回
`{"status":"ready","policyVerified":true}` 时200；其他情况503/unavailable/false。
health在runtime专用线程执行preflight(journal.pending())、require_live和execution/image
配置核对；存在pending记录则不ready。还必须有当前镜像60秒内真实执行经过HostGuard、
响应验证和确认清理的记录。首次health固定执行JS `function main(i) { return 1+1; }`，
严格要求output=2并清理成功；该操作确实会创建容器，只由协调者真实部署时调用。
静态配置、空daemon或HostGuard对象存在本身不能产生policyVerified。记录仅在内存，
重启需重新probe；过期本次health拒绝，下次可重新probe。不能据此声称持续硬约束已被证明。

RPC采用4字节network-order长度+UTF-8 JSON，单帧1..65536字节，拒绝重复键/过深/非有限数；
请求固定字段op/owner/deadline，execute另有request，status/cancel另有requestId，health
无其他字段。deadline为同宿主monotonic绝对时间，必须剩余>0且≤25秒；不支持跨宿主。
完整帧包含封装，因此接近64KiB的HTTP请求可能因RPC封装超限而被拒绝，不做分片。
client每次只连接一次、发送一次，不重试。连接读写按剩余绝对期限设置timeout。
服务端默认8连接线程，runtime单线程且无等待队列，忙则拒绝；core SQLite连接只在该
线程创建/使用。core负责原执行journal；RPC独立metadata只存id/owner/hash/status，
容量1024且满时拒绝，不含源码、输入、输出；结果仅内存60秒。重启metadata统一unknown，
相同ID不重派；不做自动reconcile，也不自动清理其他进程/遗留容器。

RPC信任边界是Linux SO_PEERCRED的固定control专属UID，字段owner仅在该UID被认证后
才能代表配置allowlist中的mTLS服务身份。该UID的任意进程均有此委托权，必须专属control、
不能与guest/其他服务共用；不是只检查任意client提交的owner。client还校验orchestrator
peer UID。socket parent必须control UID拥有且0700，socket同UID且0600，路径无symlink。
orchestrator可作为root进入该目录并chown socket；客户端保持非root专属UID。
生产control必须是专属非root UID17001，不能加入docker组、持有额外capabilities或共享
orchestrator的runtime凭据/目录权限。RPC层保留 `--allow-root-control` 仅供显式测试，
control HTTP CLI始终拒绝root，不提供绕过开关。UID0的control仍可读取root runtime socket，
不能用root control声称权限隔离成立。
已存在socket路径拒绝启动，不自动unlink；停机留下端点须协调者核对进程后处理。

当前core无可并发取消接口：运行中cancel不触碰adapter，返回cleanupVerified=false；
只有本进程已验证执行收尾的terminal记录才确认清理。HTTP据此失败关闭；运行仍由现有
绝对deadline及独立reaper负责。本阶段没有修改core/adapter/reaper来假装支持立即取消。

真实启动参数示例（**仅文档，本次没有执行**；先按既有流程启动独立reaper并获取session）：

```bash
python3 -B -m supervisor.orchestrator_rpc \
  --rpc-socket /run/easyshell-code/control-rpc/orchestrator.sock \
  --control-uid 17001 --owner server.internal \
  --metadata /private-orchestrator/rpc.sqlite \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id '<可信预登记daemon ID>' \
  --image 'sha256:<已验收IsolationWorker镜像64hex>' \
  --journal '<reaper已创建的私有execution journal>' \
  --reaper-session '<独立reaper ready/session>'
```

受信部署预先创建0700的control-owned RPC目录和0700的orchestrator-owned metadata目录；
CLI使用umask077，metadata存在时要求本UID拥有、regular file、0600。runtime socket
仍只在独立orchestrator可见，Java/control/worker均不可挂载；原reaper配置不变。
客户端请求与RPC输出均不写内容日志。当前没有服务安装、网络监听或真实环境验收。

目录与SO_PEERCRED规则明确如下：由root预先创建RPC目录，chown为17001、mode0700；
root orchestrator进入目录bind后，将socket chown为17001、mode0600。服务端检查目录和
socket owner均为配置control UID17001；control客户端也检查相同归属和权限，再通过
SO_PEERCRED验证服务端进程UID=0。orchestrator通过SO_PEERCRED验证客户端UID=17001。
socket文件归属不等于服务端进程身份。不可把目录改为root:0700（17001无法访问），
也不需要放宽到0710/0660。目录路径祖先必须由受信部署控制，不能让其他用户替换。

control TLS cert/key/CA文件要求UID17001拥有、regular file、0600、无symlink；
journal目录要求UID17001拥有且0700，已有journal要求0600。CLI拒绝实际real/effective UID
不匹配配置或与orchestrator UID相同的情况；不自行setuid，由部署管理器负责先降权，
清空补充组和capabilities。示例假定已建立专属用户easyshell-code-control，UID/GID17001：

```bash
runuser -u easyshell-code-control -- python3 -B -m supervisor.control_server \
  --listen-host 127.0.0.1 --listen-port 9443 \
  --cert /private-control/tls/server.crt \
  --key /private-control/tls/server.key \
  --ca /private-control/tls/client-ca.crt \
  --client-dns server.internal \
  --rpc-socket /run/easyshell-code/control-rpc/orchestrator.sock \
  --control-uid 17001 --orchestrator-uid 0 \
  --journal /private-control/control.sqlite
```

以上仅是协调者启动参数，不是本轮执行记录。代码目录需对该用户只读可访问，TLS目录
需对其可遍历；runtime socket目录仍仅orchestrator可访问。非root身份与部署访问控制共同
提供权限边界，不能仅凭没有导入DockerAdapter就声称隔离。旧 `/v1` 路径不提供别名。

本阶段fake验证：

```bash
python3 -B -m unittest discover -s supervisor -t . -p 'test*.py' -q
```

`test_control_server` 已同步更新为内部路径与同步200契约。测试覆盖同步HTTP、health、
peer UID、目录/socket归属和权限、非root CLI、TLS文件归属、owner、framing、冲突/重启
不重派、取消失败关闭与清理未知；全程fake，无真实监听或凭据读取。

## 内部 mTLS HTTP 控制面（默认503，不启用 guest）

`control_server.py` 提供显式受信CLI与入口库，无环境启用开关。受信部署代码
可构造 `TLSConfig`、`ControlService` 和 `ControlHTTPServer`。既有 supervisor core、
Java worker/server、默认拒绝入口均不接线。缺 backend 的有效提交返回
`503/failed/RUNTIME_UNAVAILABLE`。TLS 必须显式提供 cert/key/CA 和非空客户端 DNS allowlist，
使用 `CERT_REQUIRED`、最低 TLS1.2；客户端证书必须恰有一个 DNS SAN 且精确命中列表，
CN、通配符和 HTTP 身份头均不能授权。`dispatch(cert, ...)` 仅供 TLS 验证后的内部调用。

进程边界：Java server → mTLS ingress → 受信 Unix RPC facade → 独立
orchestrator → 专用 runtime socket。Java server、ingress、worker 均不得获得 Docker
socket 或 DockerAdapter；既有 Supervisor 只能位于受信 orchestrator 中。
`ExecutionBackend` protocol由UnixRPCBackend实现；尚未真实部署或启用本入口。
RPC验证 peer 身份及文件权限，限制帧大小/IO绝对期限，绑定 owner/id，
不允许请求选择命令、路径、镜像或 runtime 配置；交付未知不得重试，确认清理才能报告成功。

| 接口 | 行为 |
| --- | --- |
| `POST /internal/v1/code-executions` | 固定五字段；同步200结果，未知503 |
| `GET /internal/v1/code-executions/{requestId}` | requestId/status |
| `DELETE /internal/v1/code-executions/{requestId}` | 确认清理200 cancelled，否则503 |

资源绑定 mTLS service identity；跨客户端 GET、DELETE、重复 POST 均403。
同 owner 同 ID 同规范化内容200返回当前状态，不同内容409；不存在404。
POST结果格式为 `{requestId,status,output}` 或 `{requestId,status,errorCode}`。unknown不意味着
guest已停止，独立 orchestrator/reaper 必须负责清理。原 ID 永不重派，不自动换 ID 重试。

约束复用 core：完整 JSON ≤65536 UTF-8字节，源码 ≤32768字节，timeoutMs整数1..5000，
语言仅javascript/python，深度≤64，元素≤10000；拒绝多余字段、重复键及非有限数。
POST要求 Content-Type:application/json 和定长body；拒绝chunked/Transfer-Encoding、
重复Content-Length、Expect及资源请求多余body。每连接只处理一次，默认绝对25秒覆盖
TLS握手、header、body、响应。默认16连接、4 backend线程、8等待槽；每身份每分钟120次
认证请求，容量拒绝429。RPC预算23秒含排队；core执行预算15秒启动宽限+timeoutMs，
另留有界清理预算，剩余RPC预算不足则拒绝执行。
取消使用同一有界线程池，排队也占2秒取消期限；超期unknown。backend必须自行约束RPC和
执行绝对期限；Python入口不能强杀失控backend线程。未认证连接受连接上限和期限约束。

SQLite仅存 request_id、SHA-256、owner、status，不存源码、输入、输出、异常。默认1024条，
SQLite页数有界；满时503，不删历史ID腾空间。每私有journal只支持一个进程/实例，文件及
目录权限由受信部署保证。结果仅驻内存，每项≤65536字节，项数受journal容量约束，TTL默认
60秒；到期不可获取，在后续认证请求惰性移除。重启历史记录统一unknown，结果不可用，
不恢复执行。未知状态须受信编排方核对。HTTP不记录URL、身份、请求/结果/异常原文，
响应均Cache-Control:no-store；部署代理也不能记录body。

此处只校验结果结构和大小，不增加掩码实现。mTLS结果仍可能敏感，Java server必须使用
既有 `SensitiveDataFilter` 唯一实现，在返回用户、持久化或日志前脱敏。

Java客户端连接约定（本次不改Java）：受信部署提供专用客户端key managers及内部CA
trust managers初始化 `SSLContext`，交给 `HttpClient`；保留HTTPS hostname verification，
固定base URL主机名匹配服务端DNS SAN，禁止trust-all。客户端证书唯一DNS SAN必须命中
ingress allowlist；轮换可保留相同DNS身份。固定连接/请求超时和响应上限；POST可使用
`BodyPublishers.ofByteArray`保证Content-Length，禁止chunked。超时/断连先用同ID查询；
同内容重复POST只取现状，不自动新建ID；GET/DELETE使用同一服务证书。owner是服务身份，
Java仍须验证业务用户的数据级权限。TTL过期只表示结果不可用，不能重新执行该ID。

fake验证（无监听、容器、网络或真实凭据）：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control_server -v
```

覆盖认证、三种资源操作跨身份403、大小/深度/字段约束、重复/冲突、队列/限流、取消验证、
TTL、重启不重派及磁盘无明文结果；fake TLS socket检查framing、CERT_REQUIRED及握手期限。
这不替代真实TLS握手、部署隔离、Unix RPC及容器验收，本阶段未启用guest入口。

## 独立 reaper 的 supervisor SIGKILL 验收

协调者已报告 execution-review05 四项真实通过（JS/Python normal、两种 worker TIMEOUT，
均 cleaned）；本工具未重复连接或独立核验该真实结果。新增 `crash_acceptance` 只执行
固定 JS 无限循环、guest timeoutMs=5000，不接受源码、命令或任意payload。

先使用全新 journal 路径按下文命令启动独立 reaper，取得 ready/session。不要复用已有
四案例 journal：崩溃验收要求初始 tasks 表为空、专用 daemon 无任何容器。保持 reaper
独立运行，在另一个可信宿主终端执行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.crash_acceptance \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<已通过execution的固定镜像ID>' \
  --journal '<新reaper创建的同一journal路径>' \
  --reaper-session '<该reaper ready的session>'
```

父进程只通过固定 `sys.executable -m supervisor.crash_acceptance --child ...` 创建一个
child supervisor；子进程环境为空（仅固定 PYTHONDONTWRITEBYTECODE），stderr丢弃。
单向观测 pipe 只允许按顺序 LIMITS_VERIFIED / PAYLOAD_DELIVERED，最多64字节。
真实 guard 成功才报告第一个事件；stdin完整写入及SHUT_WR成功才报告第二个事件。
后者回调阻塞在父子控制pipe，父不释放它，只 SIGKILL 自己的 Popen child，验证返回码
为 -SIGKILL。它不读取任务/daemon提供的PID来杀止其他进程；default CLI未开放。

父总期限30秒（各Docker读取仍受其独立短期限），期间等待固定20秒任务租约到期。
只有SIGKILL完成、独立reaper仍活着、journal任务terminal、同session租约已到期、
HostGuard保存的cgroup消失或populated=0且procs为空、最终daemon空，才报告
`passed / reaper_after_sigkill / cleaned=true`。父没有容器cleanup/reconcile接口调用，
不能用finally自清理冒充reaper证据；子阻塞闸门阻止其正常finally先清理。
这是输入交付后的supervisor crash，不证明guest main已经进入。

阶段不足/超时或证据失败返回CRASH_ACCEPTANCE_UNVERIFIED、cleaned=false；父只杀止
自己的子进程，容器继续由独立reaper负责。保留journal诊断，不删除未知容器、不盲重试。
`--child` 为内部固定模式，不接受执行代码，不是业务服务或默认执行开关。

2026-11-11 完整八模块fake测试通过84项，0失败/错误/跳过；新增观测回调顺序、固定事件
拒绝、精确Popen杀止、等待reaper证据、terminal/租约/cgroup/daemon条件验证。
没有真实创建进程进行SIGKILL测试、连接daemon或运行容器；真实验收由协调者执行。

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control supervisor.test_attach supervisor.test_acceptance supervisor.test_diagnostics supervisor.test_profiles supervisor.test_review supervisor.test_deadlines supervisor.test_crash -q
```

## 启动宽限与 TIMEOUT 来源（当前约定）

每任务外部绝对 deadline 固定为接收后 `15秒 + timeoutMs/1000`，最大20秒。
startup allowance 不来自请求；guest timeoutMs 仍为1..5000，非法新增字段照常拒绝。
reaper 保存完全相同的绝对 deadline，authorize/reaper 拒绝超过20秒的租约。
Java Context build 和显式 language initialize 在执行 future 前，归外部总期限覆盖；
guest timeout 从 future 中用户 source eval 开始，main 和输出序列化同属 guest预算。
没有初始化完成消息，没有phase字段，没有多消息协议：响应仍仅 status/error或output。

Supervisor 内部 `last_timeout_origin` 为 none/worker/external：严格解析单个 worker JSON
得到 failed/TIMEOUT 才记 worker；外部期限 catch TIMEOUT 记 external。正常输出仍须清理
后才成功。loop acceptance 必须同时是 failed/TIMEOUT、worker来源、delivery=true；
启动/guard/attach 期间外部超时不得假通过。delivery只表示完整stdin+半关闭，不表示main
已进入。可信验收报告附 timeout_origin 不改变worker输出协议。cleanup有独立短期收尾
预算，20秒是任务执行截止，不能声称收尾总耗时也已经被20秒硬进程监督覆盖。

协调者复验前替换新installDist中的worker JAR并更新固定镜像ID（资源/依赖未变无需
重导出），以fresh journal启动独立reaper，再用同session运行execution_acceptance。
本轮没有真实daemon操作，不保证normal Python一定在20秒内完成，须以真实结果为准。
本轮完整七模块fake回归通过79项，0失败/错误/跳过；JDK21 offline test installDist成功。
新版分发JAR已生成；没有重导出资源或构建镜像。

固定 COMMAND 已增加官方只读资源与 fallback JVM 参数，见 worker README 的资源预提取
流程。每个 profile 的命令仍固定，默认 refusal；新镜像需内置
`/opt/code-worker/resources`，不能直接使用缺资源的旧镜像。未改变 Env=[]、guest IO/native
限制或 /tmp noexec。75 项 fake 测试通过；空环境本机固定 Python probe 成功，但仍有
TruffleAttach warning，尚未确认真实容器初始化问题解决。

Python case1 诊断更新：result_error 白名单覆盖本代码全部固定失败码，并新增静态回归
防遗漏；没有 error 的 succeeded 响应显示 `none`，不再误显示 unknown。CASE_UNVERIFIED
仍不回显 output/source。只读 execution-review-03 journal 确认两任务 terminal、两条 guard
记录；journal 不存结果，不能从中推断 Python 根因。并发修改期间结果仍不作为语言证据。
新增固定 Java 主类 `com.easyshell.codeworker.PythonDiagnosticWorker`（见 worker README），
未接入任何动态命令选择。该诊断只 eval 1+1，保持 IO/native 禁用；需重建 dist/诊断镜像，
stdout 为固定成功或 DIAGNOSTIC_FAILED，stderr 仅类名/类别/阶段。当前 profile 不自动
切换到诊断主类，也不收集普通任务 stderr。

2026-11-11 安全诊断更新后完整六模块命令通过 74 项 fake 测试，0 失败/错误/跳过；
独立 Gradle JDK21 offline test 同时通过。没有连接 daemon或执行容器。

## v2 execution 验收候选（尚未真实运行）

### Review 修补后的启动流程（取代下文旧的直接启动命令）

本轮五项修补与安全诊断已落地，协调者执行前必须启动独立 reaper。先选全新 journal
路径；由 reaper 独占创建，acceptance 随后打开同一文件。两进程必须在同一可信宿主
namespace/boot 中运行，目录私有持久，参数固定同一 socket、daemon ID、镜像和 execution
profile。不需要因本轮 Python 代码修改重建 Java dist；若尚未打包 IsolationWorker，仍需
先重建 dist 和镜像。默认 CLI 和 refusal profile 均未开放或切换。

先在独立终端/受信服务中启动（不要在任务容器内启动）：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.reaper \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<含 IsolationWorker 的固定镜像 ID>' \
  --journal '<私有持久目录>/execution-review-new.sqlite'
```

等待该进程输出 `status=ready`，取其随机 session；保持 reaper 运行，再显式验收：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.execution_acceptance \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<同一镜像 ID>' \
  --journal '<同一 journal 路径>/execution-review-new.sqlite' \
  --reaper-session '<ready 返回的 session>'
```

reaper 每 0.2 秒检查 journal；任务使用不可续期的 monotonic deadline 租约，过期或旧会话
任务只按精确 journal ownership 清理。心跳在一轮回收成功后写入；接收任务及发送源码前
检查心跳不超过 1 秒、会话匹配、独立 reaper PID/starttime 稳定。失败停止接收；session
不是 guest 凭据，不传容器。授权与 journal claim 之间使用短暂有界占位租约，避免竞态
将尚未创建的任务当孤儿；这不替代部署管理器监控 reaper。异常退出不会自动重启服务。

五项修补的具体范围：

- Attach/协议解析失败后仍 Inspect 已绑定且 owned 的容器；仅 `OOMKilled is True`
  映射 RESOURCE_EXHAUSTED。Inspect 不可证实返回 STATE_UNVERIFIED，清理失败仍覆盖。
- guard 在发源码前持久记录可信 cgroup 路径及 device/inode；删除容器后必须该目录消失，
  或同一 inode 的 cgroup.events populated=0 且 cgroup.procs 为空。未知/权限不足拒绝清理成功。
- Attach 只有完整输入发送且 SHUT_WR 成功才记录 delivered；loop TIMEOUT 在 delivered
  不为 True 时不可验收通过。它证明交付阶段，不证明已进入 guest 循环体。
- 普通 HTTP 每次有绝对期限（任务剩余期限与单次 2 秒取最小），定时器在期限到达时
  shutdown socket，限制持续慢速 header/body。Attach 继续用自身绝对期限。cleanup 使用
  单独短期预算。该方案仍依赖可信宿主线程调度，不是独立进程强杀的完整替代。
- 独立 journal reaper 已可启动；尚未由协调者验证真实 kill-supervisor。若 supervisor
  在保存 cgroup 身份前崩溃，无可信记录不得声称清理完成，即便容器已删除也保留 uncertain，
  reaper 心跳停止/进程失败，需受信方诊断，不能删除 journal 后重试冒充通过。

CASE_UNVERIFIED 现在附带白名单 result_status、result_error、stage，不含 output/source/
原异常。可区分 TIMEOUT、EXECUTION_FAILED、RESOURCE_EXHAUSTED、REAPER_UNVERIFIED 等；
但 EXECUTION_FAILED 不揭示 Python 模块/权限的具体异常，仍不能据此猜测启动原因。
stage 取 claimed/preflight/created/started/limits_verified/source_delivered/response_received/
response_validated；stage 和语言执行结果分开报告。此前并发修改期间 RUNTIME_UNCERTAIN
不作为语言证据；JS case0 通过、Python case1 失败只沿用协调者旧完整版本的观察。

完整 fake 回归：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control supervisor.test_attach supervisor.test_acceptance supervisor.test_diagnostics supervisor.test_profiles supervisor.test_review -q
```

2026-11-11 本阶段完整命令通过 73 项 fake 测试，0 失败、0 错误、0 跳过；其中包含
继承的 guard 回归。未连接 daemon、运行容器或读取真实任务 cgroup；真实复验待协调者。

可信部署配置 `Config(..., profile="execution")` 显式选择固定 IsolationWorker；
`profile="refusal"` 为默认，继续固定原 Worker。请求没有 profile/command 字段，默认
`python3 -m supervisor` 仍拒绝，原 `supervisor.acceptance` 仍是 v1 refusal 验收。
v2 使用 `easyshell-code-v2-` 名称前缀及 `easyshell.code.execution.v2` 协议标签，
adapter 拒绝跨 profile journal 记录。journal 增加 profile 列，无旧 schema 自动迁移；
必须为每次验收提供全新文件，保留旧文件作为证据，不删除/复用旧 journal。

execution profile 在固定 Start 后、stdin 发送任何源码/输入之前，必须调用受信
host guard；默认 guard=None 即拒绝。内置 `HostGuard` 从 Docker Inspect 取得宿主 PID，
只读取受信宿主 `/proc/<pid>/stat`、`/proc/<pid>/cgroup` 和 `/sys/fs/cgroup`：

- 使用 cgroup v2 唯一路径，必须包含完整 container ID 或 `docker-<ID>.scope`。
- 路径必须位于受信 cgroup 根目录下，无跳转/符号链接；PID 必须在 cgroup.procs 中。
- 前后读取 starttime 和 cgroup 路径，防 PID 复用/迁移；读取必须在 deadline 内完成。
- 实际 memory.max=536870912、memory.swap.max=0、pids.max=128，
  cpu.max=100000 100000；任一不存在、权限不足、不匹配均拒绝发送源码并清理。

该检查必须在可信宿主 PID/mount namespace 中运行，真实挂载的 procfs/cgroupfs 必须由
部署侧保证；不能把 guest 或伪造的目录传给生产 guard。cgroup namespace 为 private
不影响宿主 `/proc` 所见路径；不要在任务容器内运行 guard。检查是启动时快照，持续
限额与 cgroup 清空、独立 watchdog、HTTP 总期限和协作取消仍未完成完整证明。

协调者先用 JDK21 重建 `installDist`，将新 dist/lib 与 JDK21 放入无 ENV/秘密/挂载声明
的固定镜像，取得新的本地 `sha256:<64hex>` 配置 ID（旧 refusal 镜像不含新类不能复用）。
本轮没有构建 dist/镜像或连接 daemon。完成后在 worker 目录显式执行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.execution_acceptance \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id e9ae18cb-8f81-492c-a39b-91c40f5c18bc \
  --image 'sha256:<含 IsolationWorker 的新镜像配置 ID>' \
  --journal '<可信私有持久目录>/execution-v2-new.sqlite'
```

必须使用空专用 daemon（含已停止容器均为空），不得接宿主 default/trial runtime。
脚本顺序执行 JS 正常、Python 正常、JS 无限循环、Python 无限循环各一次，每次都要求
清理且空 daemon；正常结果严格为 succeeded/output/n=4，循环严格为 failed/TIMEOUT。
失败不盲重试；保留 journal 供受信清理和诊断。Start/初始化也占任务总 deadline，循环
案例可能在语言初始化阶段被外部超时，不应据此声称证明循环内 context 取消路径。
脚本是显式执行验收入口，不是 server 服务接线；只通过 fake 测试不能声称真实执行通过。

新增 fake 验证可运行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control supervisor.test_attach supervisor.test_acceptance supervisor.test_diagnostics supervisor.test_profiles -q
```

2026-11-11 上述命令通过 58 项 fake 测试，0 失败、0 错误、0 跳过。新增覆盖 profile
命令/归属隔离、guard 缺失或失败时不发送源码、模拟真实文件读数、权限不足、PID 复用、
错误容器路径，以及四个固定验收案例的结果契约。没有访问真实 daemon 或宿主任务 cgroup。

本节后续描述保留默认拒绝 CLI 的边界，而非声称整个隔离服务均未实现。
独立临时运行时已完成实际容器、mTLS、业务接线、取消、OOM 和独立回收验收；生产
systemd/固定基础镜像构建仍待验收。原 Worker 与默认 CLI 保持拒绝，可信 execution
策略通过固定 native launcher 执行 IsolationWorker，启动 JVM 时使用空环境。

## CLI 与信任边界

从独立 worker 目录运行 `python3 -m supervisor`，stdin 接收一个以 EOF 结束、
最多 65536 UTF-8 字节的 JSON；stdout 只返回一个稳定 JSON 错误，无堆栈或请求回显。
有效请求包含且仅包含：

```json
{"requestId":"example-1","language":"python","source":"def main(i): return i","input":{},"timeoutMs":1000}
```

有效请求始终返回 `ISOLATION_UNVERIFIED`，没有启用参数或环境开关。
CLI 不实例化 DockerAdapter、不读取可信配置、不访问 socket、不创建 journal。
非法字段、重复键、超深/过大 JSON、NaN、非法语言/超时均拒绝。
源码最多 32768 UTF-8 字节，JSON 深度最多 64，元素最多 10000。
stdin 等 EOF 的耗时不在模型的执行 deadline 内；未来托管进程必须额外限制输入期限。

DockerAdapter 的配置只能由受信部署侧调用 `Config.from_environment()` 获取：

| 变量 | 约束 |
| --- | --- |
| `EASYSHELL_CODE_SOCKET` | `/run/easyshell-code/<专用名称>/docker.sock`，拒绝系统 socket 和 symlink |
| `EASYSHELL_CODE_DAEMON_ID` | 预先可信登记的独立 daemon ID，逐操作读取 `/info` 核对 |
| `EASYSHELL_CODE_IMAGE` | 固定 `repository@sha256:<64 lowercase hex>` 或本地 `sha256:<64 lowercase hex>` 配置 ID；拒绝 tag |

请求不能选择镜像、命令、环境、挂载或资源限制。这些可信配置绝不传给 guest。
Inspect 同时校验容器 `Config.Image` 与配置引用完全一致，顶层 `Image` 为完整合法的
`sha256` 配置 ID。使用本地 ID 时顶层 `Image` 还必须与配置值完全一致；使用仓库
manifest digest 时不将它与 image config ID 比较，两者本来可能不同。该路径依赖可信
daemon 对 digest 的解析，不查询镜像 RepoDigests，也不允许用 tag 替代原始引用。
配置解析不代表实际连接获准；本次只能 fake 测试，不得尝试现存 daemon。
socket 父目录、journal、配置必须由受信部署侧持有，禁止其他用户修改或替换。

**socket 权限范围依赖真正独立的 daemon**：路径检查、ID 和空容器清单不能证明
独立 data-root、exec-root、containerd namespace、访问控制及宿主权限隔离。
部署须单独证明这些条件、专用 daemon 无其他客户端，且 ID 不是从共享 daemon 抄来。
该 socket 本身仍有其 daemon 的完整权限，Python 适配器不是宿主权限隔离机制。
未来 server 和 worker 控制面均不得持 socket；特权适配器属于独立受信部署设施。

## 固定 API 与资源策略

没有 shell、Docker CLI、任意命令/API 透传。固定 Docker API v1.45：

- 只读预检：`GET /info`、`GET /containers/json?all=1&limit=1025`。
- 固定策略 Create、归属容器 Inspect、固定 Start、单次 Attach、SIGKILL、非强制且不删卷的 Remove。
- Start 不接受命令或配置，Attach 路由固定 `stream=1&stdin=1&stdout=1&stderr=1&logs=0`。
- 无 Exec、日志读取、镜像 pull/build/prune 或卷操作。

预检要求匹配 daemon ID、cgroup v2/systemd、builtin seccomp、AppArmor。
容器清单必须为空或全部匹配本地 journal 已登记的 name、ID、协议标签和随机 nonce。
不因有协议 label 就认领；未知容器使预检失败，不杀止或清理未知容器。
普通 HTTP 响应最多 1MiB，socket I/O timeout 为 0.5 秒；不输出 daemon 错误正文。
Attach 单独使用绝对 deadline，每次读写前重新设置剩余时间；握手头最多 8192 字节。
握手仅允许 101 Upgrade/tcp，Content-Type 精确匹配 Docker raw-stream 或
multiplexed-stream 两种 MIME；不接受 200、未知 MIME 或额外 MIME 参数。
依据：[官方 API v1.45 ContainerAttach](https://github.com/moby/moby/blob/v28.0.0/docs/api/v1.45.yaml#L7968)
明确非 TTY 为 multiplexed-stream；
[Docker 29 实现](https://github.com/moby/moby/blob/docker-v29.0.0/daemon/server/router/container/container_routes.go#L1042)
对非 TTY、API >=1.42 返回 multiplexed-stream 与 101 UPGRADED。
raw-stream 兼容旧的握手表示，不启用 TTY：两种 MIME 均强制同一 8 字节 multiplex 帧
解析，裸 JSON、未知 stream、非零保留字节和截断仍拒绝。

`control.policy()` 固定策略，`control.verify()` 对对应 Inspect 字段逐项严格核验：

| 项目 | 固定值 |
| --- | --- |
| 镜像、命令 | 可信 digest；固定 JVM 和原本仍拒绝执行的 Worker 主类 |
| 身份、提权 | UID/GID 65532，readonly root，cap drop ALL，无 cap add，no-new-privileges |
| 网络 | NetworkDisabled，network none，无端口发布 |
| 内存/swap | Memory=512MiB，MemorySwap=512MiB（Docker 合计口径，禁额外 swap） |
| CPU/PID | period=100000、quota=100000，即 1 CPU；PID 128 |
| 临时存储/FD | `/tmp` 64MiB、noexec/nosuid/nodev；nofile soft/hard=256 |
| 数据与日志 | Env=[]、非 TTY、单次 stdin/attach、log driver none；输入仅有界 JSON |
| 其他 | 无宿主挂载/设备/卷继承；独立 PID/IPC/cgroup namespace；无自动重启 |

Inspect 严格缺失即失败，拒绝意外卷/挂载及运行状态不一致。
唯一字段缺省例外是 `HostConfig.Mounts`：仅当顶层 `Mounts` 明确为 `[]`、无镜像
`Config.Volumes` 声明，且 `HostConfig.Binds`、`VolumesFrom` 字段存在并为 null/空数组
时允许省略。非空 Mounts、顶层 Mounts 缺失、legacy 挂载字段缺失/非空仍拒绝。
Docker 对空字段、tmpfs 挂载的表示可能不同；当前有意采用保守拒绝，尚未通过真实
daemon 验收，不能靠放宽测试断言声称可运行。Env=[] 在 Create 中不证明镜像没有默认
ENV，Inspect 必须为空；实际镜像还需专用空环境启动器及镜像内容审查。
固定镜像仍需检查默认 capabilities、seccomp/AppArmor 实际施加及无凭据内容。

## 生命周期模型与失败语义

`Supervisor.execute()` 是内部部署 API，CLI 不调用它。adapter 已实现固定 Start/Attach，
但从未连接真实 daemon 验收；镜像中的固定 Worker 入口仍应拒绝执行，未开放执行核心。
不得将模型中的 fake 成功结果称为真实运行通过。

- 首先在 SQLite journal 登记 requestId、内容 SHA-256、确定容器名和随机 nonce，
  再预检及 Create。journal 不存源码、输入、输出；固定容量 1024 条且限制页数，满即拒绝。
- 相同 ID/相同内容返回 DUPLICATE_REQUEST，内容不同返回冲突，均不重建容器。
  不缓存/重放输出，不保证 exactly-once；首次请求的终态不能据此重试。
- 顺序为 Create → Inspect 完整验证 created 状态 → Attach 握手 → 固定 Start →
  发送最多 65536 字节输入并 `shutdown(SHUT_WR)` → 读完结果 → Inspect 确认退出 → 清理。
  Attach 必须在 Start 前完成，以捕获短命进程 stdout（log driver none 不提供历史重放）。
  输入只含 language/source/input/timeoutMs，不传 requestId 或可信配置/环境/凭据。
- 执行 deadline 从预检前开始，使用单调时钟，涵盖启动/通信/退出轮询。
  Docker raw multiplex 的 8 字节帧头严格校验 stream=1/2、保留字节为零、长度合法；
  stdout 可跨多帧，累计最多 65536 字节，最终响应 JSON 编码亦有相同上限。
  stderr 不解码、不记录、不进入结果；仍计入总流量预算（含帧头最多 196608 字节），
  总帧数最多 4096，防止 stderr 或空帧洪泛。EOF 截断、错误握手、未知 stream 均拒绝。
- stdout 必须是单一严格 JSON 协议：succeeded/output 或 failed/已知稳定错误码；
  多 JSON、重复键、未知错误内容和多余字段均拒绝。只在退出码 0、非 OOM、已退出时
  才能接受结果；不要求轮询捕获短命进程的 running 瞬间。
- 固定 Worker 正常响应应为 `ISOLATION_UNVERIFIED`，fake 已覆盖该响应解析；
  但 Worker 不读取 stdin，真实发送/半关闭可能遇到连接关闭，此时只能按通道失败清理，
  不能伪造或保证收到拒绝响应。没有为此更改 Java 入口。
- Start/Attach/写入/半关闭/连接失败不盲重试；通道必须关闭，关闭失败也不能返回成功。
- 异常、超时及成功结果都进入清理；先匹配 journal 归属，再检查状态、必要时 SIGKILL，
  确认 Running=false 且 Pid=0，再 Remove，最后 Inspect 必须 404。
- Create 响应丢失或非法不盲重试，按已登记 name/nonce 检查并清理；若创建结果未知且
  name 为 404，仍判不确定，避免迟到创建被误认为安全。
- Inspect/资源核验失败不报成功；清理失败覆盖原结果为 CLEANUP_UNVERIFIED，journal
  标记 uncertain。`reconcile()` 仅处理 journal 中 pending/uncertain 的已知资源。
- reconciler 目前只是可调用机制，没有独立常驻 watchdog；监督进程被杀后不会自动回收。

当前 deadline 不是外部强制 wall-clock：Attach 数据通道有绝对剩余时间，但普通 HTTP
（包括 Start 及其前置检查）的 0.5 秒 socket timeout 不能限制持续慢速响应的总时长；
调用返回后会再次检查 deadline，仍可能迟到。cleanup_grace 只约束轮询，不约束全部 HTTP 总耗时。
没有 `context.close(true)` 请求通道、客户端断连处理或独立崩溃回收器。
Running=false/Pid=0 和 404 是 Docker 元数据，**未直接证明 cgroup 为空或硬限制生效**。
未来真实验收须补外部总期限、cgroup 实测、独立租约回收及协作取消通道；
不能直接给本原型增加执行开关来绕过这些缺口。

## 仅 fake 测试

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control supervisor.test_attach -v
```

全部 runtime 操作由 fake 替代；Docker adapter 测试覆盖 transport 方法，完全不访问
socket。覆盖超时强杀、清理失败、删除未生效、创建响应丢失、Inspect 异常、限制缺失、
非归属容器、重复/冲突 ID、容量、reconcile、默认关闭 CLI 和专用 daemon 预检。
这些测试证明模型分支，不能证明 Docker API、资源硬限制或沙箱部署成立。

另覆盖固定 Start/Attach 路由、启动失败、stdout 多帧、stderr 丢弃、超大/畸形帧、
慢速读取截止、半关闭及连接失败、不重试、退出码/OOM、拒绝响应和删除后 ID 的 404。

2026-11-11 实际运行上述命令：39 项测试全部通过，0 失败、0 错误、0 跳过。
没有运行容器、连接 Docker socket 或下载新增依赖；测试 journal 均在内存中。

## 专用 daemon 的一次性拒绝入口验收（脚本已准备，尚未真实运行）

`acceptance.py` 是显式调用的验收工具，不改变默认 `python3 -m supervisor` 拒绝行为。
协调者准备完环境后，从 worker 目录运行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m supervisor.acceptance \
  --socket /run/easyshell-code/acceptance/docker.sock \
  --expected-daemon-id '<协调者可信登记的全新专用 daemon ID>' \
  --image 'sha256:<本地 image config ID 的 64 hex>' \
  --journal '<可信私有持久目录>/acceptance-new.sqlite'
```

前置条件由协调者保证，脚本不能从 Docker 元数据证明它们：

- 全新且专用的 daemon，独立 data-root、exec-root、containerd namespace，无其他客户端；
  不得使用宿主 default daemon、EasyShell trial daemon 或它们的 socket 别名。
- socket 位于指定 `/run/easyshell-code/<name>/docker.sock` 形式且不是 symlink，root 持有，
  不对所有人开放写权限；daemon ID 由可信部署流程登记，不从任意传入 socket 自行接受。
- daemon 启动时启用 cgroup v2/systemd、builtin seccomp、AppArmor；所有容器清单为空，
  包括已停止容器。预加载验收镜像允许存在；脚本没有 pull/build/load 接口。
- 镜像以 repository digest 或完整本地 `sha256:<64 lowercase hex>` 配置 ID 可在该 daemon
  本地解析，无 RepoDigest 的本地 scratch 镜像使用后者；不允许 tag 或缩写 ID。
  协调者先重建 worker `installDist`，
  旧分发包不能当新版产物。JDK 21 可来自本机已批准 JDK，但必须复制到镜像内
  `/opt/java/openjdk`，分发 lib 位于 `/opt/code-worker/lib`；不得 bind mount 宿主 JDK/缓存。
- 镜像固定入口仍为当前拒绝 Worker，不启用 ExecutionCore；无业务数据、凭据、默认 ENV、
  VOLUME、额外容器或持久挂载。JVM 必须能以 65532 在只读 root 和固定 tmpfs 下启动。
- journal 父目录是受信、持久、私有目录，指定文件必须尚不存在；脚本以 0600 独占创建，
  不覆盖、自动删除或重用旧证据。异常时保留 journal 供受信方核查/reconcile。
- 调用进程须由协调者外部限制总运行期限并准备独立回收；当前普通 HTTP 总期限缺口仍在。

脚本流程：先 `preflight([])` 拒绝任何既有容器，再登记单个固定验收任务，按固定策略
创建/Inspect/Attach/Start。stdin 为 `b""`，不含源码、input 或业务 payload，直接半关闭。
要求 stdout 严格返回 `ISOLATION_UNVERIFIED`；连接/闭管道错误可记录为 `closed_pipe`，
但同样必须确认进程正常退出、无 OOM、清理完成且最终 daemon 清单为空。
任意其他响应（包括 succeeded）、畸形帧、超时、资源不匹配或清理失败均验收失败。

输出只有有界固定 JSON：`status=passed` 时 observation 为 `worker_refused` 或 `closed_pipe`，
并要求 `cleaned=true`；退出码 0 表示该次有限生命周期验收通过，不表示隔离/沙箱交付。
`closed_pipe` 只能证明异常通道路径安全收尾，不能证明读到了 Worker 拒绝响应。
脚本不打印 Inspect、stderr、环境、请求数据或错误堆栈。

目前 Inspect 允许显式空集合的 Docker `null` 表示，但仍拒绝字段缺失。tmpfs 的 Mounts、
SecurityOpt/namespace 等实际表示仍需目标 daemon 验收；不匹配时保留失败证据再评审，
不得在现场跳过策略校验。尚未证明内核 cgroup 限额、cgroup 清空或独立崩溃回收。

扩展 fake 测试命令：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  supervisor.test_control supervisor.test_attach supervisor.test_acceptance -v
```

2026-11-11 此扩展命令实际通过 46 项 fake 测试，0 失败、0 错误、0 跳过。
验收脚本尚未连接任何 daemon；没有创建 daemon/容器、构建镜像或重建分发包。

策略拒绝现在附带 `policy_diff`：只含固定 allowlist 字段名和 expected/actual 值类别
（例如 missing、null、empty_array、nonempty_array、integer），不返回实际值、ENV 内容、
标签、动态键、请求或宿主路径。诊断不替代或放宽 verify，清理后仍返回失败。
复跑原 acceptance 命令即可获得该诊断；必须使用新的 journal 文件名。

2026-11-11 挂载缺省兼容及安全诊断验证：
`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest supervisor.test_control supervisor.test_attach supervisor.test_acceptance supervisor.test_diagnostics -v`
通过 50 项 fake 测试，0 失败、0 错误、0 跳过；没有访问任何 daemon。

2026-11-11 官方 Attach MIME 兼容修正后，同一四模块命令通过 51 项 fake 测试，
0 失败、0 错误、0 跳过；尚未复跑真实 acceptance，默认 CLI 仍关闭。
