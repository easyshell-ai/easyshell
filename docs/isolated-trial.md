# 独立空数据体验环境

**以下 Compose 方案尚未实际启动，也不是发布验收。** 另有一个使用独立 Docker/containerd、独立回环网络和临时 MySQL 的测试实例已在本机启动，入口 `http://127.0.0.1:18881`；它不是本文件下方的 Compose 栈。该实例实际完成双 VM 低风险执行、精确高风险审批、持久审批领取、真实 LLM 及隔离 JS/Python 代码试运行。后者依赖另行部署的 mTLS 控制面、独立编排与回收器，不能只启动下方 Compose 就获得。完整崩溃接管、多实例执行及旧库回滚尚未证明。以下步骤不能用于直接升级保留数据库。

## 边界和前提

- 必须使用专门获准的隔离 Docker daemon/context，不使用现有演示环境的 daemon、容器、服务、数据库、网络或卷；context 名称本身不能证明隔离，须先核实 daemon 身份。不要连接真实旧库，不接入已有 Agent，也不要复用已有 Compose 项目名或数据卷。
- 此文件是独立 Compose 配置，不能与原有 Compose 文件合并。所有容器仅加入项目专属的 `internal` 网络；MySQL、Redis 和 server 不发布宿主端口，Web 仅绑定试用主机的 `127.0.0.1`。`internal` 本身不能证明容器无法访问宿主网关或其他敏感服务，必须另行配置和验证宿主防火墙。网络隔离不是对 Docker 管理员、宿主机用户或高权限容器的安全沙箱；Redis 未配置认证。禁止把该环境暴露到公网或用于真实凭据、主机和数据。
- loopback 发布仍依赖 Docker 版本和宿主防火墙：旧于 Docker Engine 28.0.0 的版本可能让同一二层网络的其他主机访问该端口。使用前须独立确认隔离和防火墙，不能仅凭端口配置认定访问安全。
- 三个卷由 Compose 按 `-p` 项目名独立命名。首次使用全新项目时，镜像内的 `init-secrets.sh` 在新密钥卷随机生成数据库、JWT、加密和 Agent 注册密钥，server 入口从卷读取；配置不从宿主透传这些密钥。密钥文件在卷内以明文保存，文件权限不能阻止 Docker 管理员或宿主 root 读取。随机值与其他环境相同的概率极低，但并非数学上不可能。复用项目或卷不会重新生成已有密钥，也不再是空数据体验。
- 空数据指全新数据库，不意味着没有初始化数据：应用会创建体验管理员、内置配置、脚本模板和 Agent 定义。不要创建或连接真实主机。初始化只发生在新库中；应用还可能运行内部定时任务，**本配置没有禁用所有调度或恢复逻辑**。
- 不接入 Agent 时，可尝试手动数据工作流；LLM 需要受控模型访问，code 需要独立隔离服务且默认关闭。下方 Compose 没有配置这些依赖，也没有开启精确派发审批或持久恢复。V3 与未知任务自动重派继续不可用。AI 定时巡检是现有独立功能，不得误以为本配置已禁用全部定时任务。
- 密钥卷和 MySQL/Redis 数据卷均会保留；本指南不提供删卷或清理命令。若名称冲突或准备步骤失败，停止并由获授权人员调查，不要处理已有环境。

## 1. 冻结并审查候选源码

以下步骤在试用主机的 Bash 中进行。先人工审查当前源码、`docker-compose.trial.yml`、Dockerfile、脚本、依赖和候选提交，确认无真实凭据。构建使用的必须是包含**全部拟体验改动**的已审查提交；`git archive` 不包含未提交或未跟踪文件。若需要的改动尚未进入该提交，先停止并在获授权的隔离流程中准备候选提交，不能把旧 `HEAD` 冒充当前工作区，也不能 reset、覆盖或清理现有工作。

指定已批准的隔离 daemon context，并为每次体验生成新的随机项目名、镜像标签及本机端口：

```bash
set -euo pipefail
cd /path/to/reviewed/easyshell
TRIAL_REPO="$(git rev-parse --show-toplevel)"
: "${TRIAL_SOURCE_SHA:?必须提供已审查且包含全部候选文件的 commit}"
TRIAL_SOURCE_SHA="$(git rev-parse --verify "${TRIAL_SOURCE_SHA}^{commit}")"
git cat-file -e "${TRIAL_SOURCE_SHA}:docker-compose.trial.yml"
read -r -p '已批准的隔离 Docker context: ' TRIAL_DOCKER_CONTEXT
: "${TRIAL_DOCKER_CONTEXT:?必须明确指定隔离 context}"

TRIAL_PROJECT="easyshell-trial-$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
export EASYSHELL_TRIAL_SERVER_IMAGE="easyshell-trial-server:${TRIAL_PROJECT}"
export EASYSHELL_TRIAL_WEB_IMAGE="easyshell-trial-web:${TRIAL_PROJECT}"
export EASYSHELL_TRIAL_WEB_PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
```

核对 `TRIAL_SOURCE_SHA` 确实是审查过的候选。端口选择只检查当时能否绑定，不会预留端口：启动前仍可能被占用；冲突时应停止调查，不修改或重启占用者。项目名、端口和镜像标签不是口令，可以在安全终端检查并记录。

仅对已确认的**隔离 daemon**，只读检查随机项目名是否与现有容器、网络或本配置的卷名冲突；这些命令不连接容器、不挂载卷。任何冲突都停止，不复用资源：

```bash
TRIAL_EXISTING_CONTAINERS="$(docker --context "$TRIAL_DOCKER_CONTEXT" ps -aq --filter "label=com.docker.compose.project=$TRIAL_PROJECT")"
TRIAL_VOLUME_NAMES="$(docker --context "$TRIAL_DOCKER_CONTEXT" volume ls --format '{{.Name}}')"
TRIAL_NETWORK_NAMES="$(docker --context "$TRIAL_DOCKER_CONTEXT" network ls --format '{{.Name}}')"
if [[ -n "$TRIAL_EXISTING_CONTAINERS" ]] || \
   printf '%s\n' "$TRIAL_NETWORK_NAMES" | grep -Fx -- "${TRIAL_PROJECT}_trial" >/dev/null; then
  printf '%s\n' '项目容器或网络已存在；停止并调查。' >&2
  exit 1
fi
for volume in trial_secrets trial_mysql trial_redis; do
  if printf '%s\n' "$TRIAL_VOLUME_NAMES" | grep -Fx -- "${TRIAL_PROJECT}_${volume}" >/dev/null; then
    printf '%s\n' '项目卷已存在；停止并调查。' >&2
    exit 1
  fi
done
```

这些检查不是资源预留，若有其他进程并发创建同名资源，仍可能发生竞态；必须独占该隔离 daemon 的试用部署操作。

## 2. 从候选源码构建唯一镜像

必须先在隔离 daemon 上通过获批准的离线流程准备并审查以下本地镜像，包括其实际 digest：`mysql:8.0`、`redis:7-alpine`，以及当前两个 Dockerfile 引用的 `golang:1.26-alpine`、`gradle:8-jdk17`、`eclipse-temurin:17-jre-jammy`、`node:25-alpine`、`nginx:alpine`。标签可变，名称相同不代表内容已获信任。Compose 对所有服务均设置 `pull_policy: never`，缺失镜像时应失败，不会自动拉取。

在同一个 Bash 会话中继续。构建上下文使用候选提交的独立归档，避免把工作区中未跟踪文件、运行数据或 `.git` 一起发送给 builder；归档目录也应位于获准的隔离位置：

```bash
docker --context "$TRIAL_DOCKER_CONTEXT" image inspect \
  mysql:8.0 redis:7-alpine golang:1.26-alpine gradle:8-jdk17 \
  eclipse-temurin:17-jre-jammy node:25-alpine nginx:alpine >/dev/null

if docker --context "$TRIAL_DOCKER_CONTEXT" image inspect "$EASYSHELL_TRIAL_SERVER_IMAGE" >/dev/null 2>&1 || \
   docker --context "$TRIAL_DOCKER_CONTEXT" image inspect "$EASYSHELL_TRIAL_WEB_IMAGE" >/dev/null 2>&1; then
  printf '%s\n' '候选镜像标签已存在；停止并调查。' >&2
  exit 1
fi

TRIAL_BUILD_CONTEXT="$(mktemp -d "${TMPDIR:-/tmp}/easyshell-trial-source.XXXXXXXX")"
git -C "$TRIAL_REPO" archive --format=tar "$TRIAL_SOURCE_SHA" | tar -xf - -C "$TRIAL_BUILD_CONTEXT"
test -s "$TRIAL_BUILD_CONTEXT/docker-compose.trial.yml"

docker --context "$TRIAL_DOCKER_CONTEXT" build --pull=false --network=none \
  --build-arg "VERSION=${TRIAL_SOURCE_SHA:0:12}" \
  -t "$EASYSHELL_TRIAL_SERVER_IMAGE" -f "$TRIAL_BUILD_CONTEXT/Dockerfile.server" "$TRIAL_BUILD_CONTEXT"
docker --context "$TRIAL_DOCKER_CONTEXT" build --pull=false --network=none \
  -t "$EASYSHELL_TRIAL_WEB_IMAGE" -f "$TRIAL_BUILD_CONTEXT/Dockerfile.web" "$TRIAL_BUILD_CONTEXT"
```

`--network=none` 限制构建的 `RUN` 网络，`--pull=false` 不保证 builder 在解析基础镜像时绝不访问 registry；必须由隔离 builder/网络策略另行阻断外网。当前 Dockerfile 的依赖下载和 apt 步骤可能需要已准备好的缓存，缺少缓存时构建会失败；不要为使其通过而擅自联网。这里不使用旧 `latest` 镜像，也不证明构建、测试或镜像运行成功。

## 3. 仅在授权后启动

仍在同一 Bash 会话中，将明确的 context、唯一 `-p`、单一配置文件和 `--env-file /dev/null` 用于每次 Compose 调用。不要额外传入宿主环境文件或覆盖密钥。只对该隔离项目操作：

```bash
TRIAL_COMPOSE=(docker --context "$TRIAL_DOCKER_CONTEXT" compose --env-file /dev/null \
  -p "$TRIAL_PROJECT" -f "$TRIAL_BUILD_CONTEXT/docker-compose.trial.yml")
"${TRIAL_COMPOSE[@]}" config --quiet
"${TRIAL_COMPOSE[@]}" up -d --no-build --pull never
```

连接试用主机本地浏览器的 `http://127.0.0.1:<EASYSHELL_TRIAL_WEB_PORT>`。若从另一台机器访问，只能在获准后使用 SSH，将工作站独占的本地 loopback 端口转发到试用主机的 loopback 端口；不要监听 `0.0.0.0`。以下变量应在**工作站**按实际情况设置：

```bash
ssh -N -o ExitOnForwardFailure=yes -L "127.0.0.1:${LOCAL_PORT:?设置工作站空闲端口}:127.0.0.1:${REMOTE_TRIAL_PORT:?设置试用主机端口}" \
  "${TRIAL_SSH_TARGET:?设置试用主机 SSH 目标}"
```

## 4. 私密查看首次口令

首次启动新数据库且初始化成功后，应用会为 `easyshell` 管理员生成随机口令并写入 server 日志。关闭终端录屏、会话记录和共享屏幕，仅在可信的私密终端查看；不要将日志重定向到文件、上传、粘贴、截图或放进 shell 历史。下面的 pager 不保存搜索历史，在其中搜索 `Generated one-time initial admin password`，查看后按 `q` 退出，随后立即在应用内修改口令：

```bash
"${TRIAL_COMPOSE[@]}" logs --no-color easyshell-server | \
  LESSHISTFILE=/dev/null LESSSECURE=1 less -S
```

口令仍可能留在 Docker daemon 的容器日志中；修改口令不会自动抹除旧日志。能读取该 daemon 或日志的人可能看到口令，因此必须控制宿主和 daemon 的访问权。若没有首次口令或健康检查未通过，停止并检查隔离环境，不要连接其他数据库或更换成旧卷。
