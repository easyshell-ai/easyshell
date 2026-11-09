<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**AI 네이티브 서버 운영 플랫폼**

AI가 스크립트를 작성하고, 여러 호스트의 작업을 조율하며, 인프라를 분석하는 동안 당신은 중요한 일에만 집중하세요.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

**언어**: [English](./README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-TW.md) | 한국어 | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 왜 EasyShell인가?

기존의 서버 관리 도구들은 사용자가 직접 모든 스크립트를 작성하고, 모든 서버에 SSH로 접속하며, 모든 출력을 스스로 해석하기를 요구합니다. EasyShell은 이 모델을 뒤집습니다: **AI가 운영자가 되고, 당신은 의사 결정자가 됩니다.**

- **필요한 내용을 일상 언어로 설명** → AI가 차이점 리뷰(diff review)와 함께 운영 환경에 즉시 적용 가능한 쉘 스크립트를 작성합니다.
- **여러 호스트를 아우르는 목표 설정** → AI가 실행 단계를 계획하고, 실행하며, 결과를 종합하여 보고합니다.
- **예약 점검 작업 구성** → AI가 출력을 분석하고 봇 채널을 통해 팀에 알림을 보낼지 자율적으로 결정합니다.
- **웹 SSH를 통한 접속** → 파일 관리자, 멀티 탭, 검색 기능을 갖춘 풀 터미널을 로컬 클라이언트 없이 사용하세요.

---

## 핵심 기능

### 1. AI 스크립트 어시스턴트

> 원하는 내용을 설명하세요. AI가 스크립트를 작성합니다. 차이점을 검토하고 클릭 한 번으로 적용하세요.

AI 스크립트 워크벤치는 일상 언어로 요구사항을 설명하면 AI가 대상 OS에 맞는 쉘 스크립트를 생성하거나 수정해 주는 분할 패널 에디터입니다. 실시간 스트리밍으로 스크립트가 작성되는 과정을 볼 수 있으며, 내장된 diff 뷰가 변경 사항을 정확히 하이라이트합니다. 요약 탭에서는 수정된 내용을 한국어로 설명해 줍니다.

<p align="center">
  <img src="docs/images/AI%20Script%20helper.png" alt="AI 스크립트 어시스턴트 — diff 리뷰와 함께 실시간 코드 생성" width="90%" />
</p>

**작동 방식:**
1. **설명** — 일상 언어로 요구사항을 작성하고 대상 OS를 선택
2. **생성** — AI가 운영 환경에 즉시 적용 가능한 스크립트를 실시간으로 생성
3. **검토** — 내장 diff 뷰가 모든 변경 사항을 하이라이트하고 요약 탭이 수정 내용을 설명
4. **적용** — 클릭 한 번으로 스크립트 라이브러리에 저장하거나 즉시 배포

### 2. AI 작업 오케스트레이션

> "모든 호스트의 디스크와 메모리를 점검하고, 80%가 넘는 항목을 표시한 뒤 해결 방법을 제안해 줘." — 완료.

AI 채팅 인터페이스를 통해 높은 수준의 운영 목표를 전달할 수 있습니다. AI는 이를 다단계 실행 계획(탐색 → 분석 → 보고)으로 분해하고, 대상 호스트에 스크립트를 배포하고, 결과를 수집하며, 위험 평가와 실행 가능한 권장 사항이 포함된 구조화된 분석을 단 한 번의 대화로 제공합니다.

<p align="center">
  <img src="docs/images/AI%20task%20orchestration.png" alt="AI 작업 오케스트레이션 — 분석을 포함한 다단계 실행 계획" width="90%" />
</p>

**작동 방식:**
1. **지시** — AI 채팅에서 높은 수준의 목표를 설명 (예: "모든 호스트의 디스크 점검")
2. **계획** — AI가 목표를 다단계 실행 계획으로 분해 (탐색 → 분석 → 보고)
3. **실행** — 스크립트가 대상 호스트에 병렬로 배포되고 결과가 자동으로 수집
4. **보고** — AI가 위험 평가와 실행 가능한 권장 사항을 포함한 구조화된 분석을 제공

### 3. AI 예약 점검

> **Cron → 스크립트 → AI 분석 → 지능형 알림** — AI가 출력을 분석하고 팀에 알림을 보낼지 자율적으로 결정합니다.

cron 표현식으로 점검 작업을 예약하고 내장 스크립트 라이브러리에서 스크립트를 선택하세요. EasyShell은 일정에 따라 에이전트에 스크립트를 배포하고, 출력 결과(디스크, 메모리, 서비스, 로그)를 수집하여 AI 모델로 전송해 지능형 분석을 수행하며, **AI가 결과의 심각도를 판단하여** 조치가 필요한 경우에만 알림을 푸시합니다.

<p align="center">
  <img src="docs/images/schedule_task.png" alt="AI 예약 점검 — Cron 기반 작업으로 AI가 출력을 분석하고 지능형 알림을 결정" width="90%" />
</p>

**작동 방식:**
1. **구성** — cron 표현식 + 스크립트(라이브러리에서 선택 또는 직접 작성) + AI 분석 프롬프트 + 알림 규칙
2. **실행** — EasyShell이 일정에 따라 대상 에이전트에 배포
3. **분석** — 출력이 AI 모델(OpenAI / Gemini / GitHub Copilot / Custom)로 전송되어 지능형 분석 수행
4. **알림** — AI가 심각도를 평가하고 조치가 필요한 경우 봇 채널로 알림 푸시

**알림 모드:** 항상 푸시, 실패 시 푸시, 경고 시 푸시, 또는 **AI 자율 결정** — AI 모델이 출력을 평가하고 알림 필요 여부를 자율적으로 판단합니다.

**지원 봇 채널** ([구성 가이드](https://docs.easyshell.ai/configuration/bot-channels/)):

| 봇 | 상태 |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |
| [DingTalk (钉钉)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |
| [Feishu (飞书)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |
| [WeCom (企业微信)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원됨 |

### 4. 기능이 완비된 웹 SSH

> 실제 터미널. 실제 파일 관리자. SSH 클라이언트가 필요 없습니다.

멀티 탭 세션, 통합 파일 관리자(업로드, 다운로드, 생성, 삭제, 탐색), 터미널 버퍼 내 전체 텍스트 검색, WebSocket을 통한 영구 연결을 지원하는 기업급 웹 터미널입니다. 파일 관리와 명령 실행을 나란히 수행하세요.

<p align="center">
  <img src="docs/images/Fully%20functional%20web%20SSH.png" alt="웹 SSH — 파일 관리자와 멀티 탭을 갖춘 터미널" width="90%" />
</p>

### 5. 호스트 관리 및 모니터링

> 모든 서버의 실시간 상태를 통합 뷰로 확인하고, 일괄 작업과 에이전트 라이프사이클을 관리합니다.

호스트를 개별 또는 일괄로 관리하세요 — 폼이나 CSV 가져오기로 추가하고, 클러스터로 구성하고, 연결 상태를 모니터링하며, 원클릭으로 에이전트를 배포/업그레이드할 수 있습니다. 통합 대시보드에서 상태 지표를 한눈에 확인하세요.

<p align="center">
  <img src="docs/images/host-management.png" alt="호스트 관리 — 일괄 작업을 지원하는 통합 서버 대시보드" width="90%" />
</p>

### 6. 실시간 스트리밍 로그

> 모든 대상 호스트에서 스크립트 실행 과정을 실시간으로 확인하세요.

스크립트를 배포하면 EasyShell이 모든 에이전트의 출력을 실시간으로 스트리밍합니다. 색상으로 구분된 로그, 타임스탬프, 호스트별 필터링으로 문제를 즉시 발견할 수 있습니다 — 배치 작업이 완료될 때까지 기다릴 필요가 없습니다.

<p align="center">
  <img src="docs/images/realtime-logs.png" alt="실시간 로그 — 다중 호스트의 라이브 스트리밍 출력" width="90%" />
</p>

### 7. 보안 및 위험 제어

> 내장된 보안 장치: 승인 워크플로, 감사 추적, 작업 제한.

어떤 작업을 실행하기 전에 승인이 필요한지 구성하세요. 모든 작업은 규정 준수를 위해 기록됩니다. 역할 기반 액세스 제어로 '누가 무엇을 할 수 있는지' 제한하고, 민감한 명령은 플래그를 지정하거나 완전히 차단할 수 있습니다.

<p align="center">
  <img src="docs/images/security-controls.png" alt="보안 제어 — 승인 워크플로 및 감사 로깅" width="90%" />
</p>

### 8. MCP 서버와 AI 스킬

> AI 클라이언트가 인프라를 직접 운영 — 웹 콘솔과 동일한 리스크 엔진, 승인 워크플로, 감사 추적 적용.

EasyShell은 플랫폼을 AI 어시스턴트 안에 넣기 위한 두 가지 구성 요소를 제공합니다.

- **`easyshell-mcp`** — [Model Context Protocol](https://modelcontextprotocol.io) 서버. 호스트, 스크립트, 작업, 점검을 MCP **도구**로 노출하여 Claude Desktop, Claude Code, Cursor, Cherry Studio 등 MCP 클라이언트에서 EasyShell을 직접 제어할 수 있습니다. npm의 [`@easyshell/mcp-server`](https://www.npmjs.com/package/@easyshell/mcp-server)로 배포되며 `EASYSHELL_URL` / `EASYSHELL_USER` / `EASYSHELL_PASS` 세 환경 변수로 대상을 지정합니다. 로컬 클라이언트는 `npx -y @easyshell/mcp-server`(stdio), 원격 공유는 `npx -y @easyshell/mcp-server --port 3000`(SSE + Streamable HTTP, 비루프백 바인딩 시 `--token` 필수)를 사용합니다. 도구는 3단계 20개(코어 6 / 관리 9 / 고급 5)입니다.
- **`easyshell-skill`** — `SKILL.md` 정의. AI 어시스턴트에게 EasyShell을 **언제****어떻게** 쓸지(워크플로, 도구 카탈로그, 보안 모델)를 알려줍니다. `npx mdskills install easyshell`으로 설치하며 OpenCode는 `.opencode/skills/easyshell/SKILL.md`, Claude Code는 `.claude/skills/easyshell/SKILL.md`에 배치합니다.

**현재 범위:** `easyshell-mcp`는 **나가는** 브리지입니다. EasyShell을 AI 클라이언트에서 *호출*할 수 있게 할 뿐, Prometheus·Elasticsearch·자체 MCP 같은 **외부** MCP 서버를 EasyShell 플랫폼 *안에서* 사용하는 기능은 로드맵에 있으며 1.1.0에는 없습니다. 논의는 [issue #6](https://github.com/easyshell-ai/easyshell/issues/6)에서 이어집니다. 전체 도구 목록과 설정 예시는 [easyshell-mcp/README.md](./easyshell-mcp/README.md)를 참고하세요.

### 9. 워크플로 엔진

> 운영 매뉴얼을 �ンバ스에 올리세요. 노드 간에 값을 넘기세요. 파괴적인 작업 앞에 사람의 게이트를 두세요.

워크플로 엔진은 일련의 운영 단계를 **시각적이고, 버전 관리되며, 감사 가능한** 워크플로로 바꿉니다. 인터랙티브 캔버스 위의 방향성 비순환 그래프(DAG)에서 각 노드는 한 가지 일을 하고 결과를 하류 노드에 전달합니다. 실행은 타입이 지정된 입력으로 시작되고, 스크립트 실행과 동일한 디스패치 경로를 지나며, 노드 단위로 기록됩니다.

**노드 유형(5가지):**

| 노드 | 역할 |
|------|------|
| `script-exec` | 대상 호스트(agent 지정 / 클러스터 / 태그)에서 스크립트를 실행합니다. 실제 **Task**로 귀결되므로 감사, 실시간 로그, 타임아웃 감시, agent 단절 처리는 새로 만들지 않고 상속합니다 |
| `condition` | 표현식(`nodes.check.output.usage > 90` 등)을 평가해 `output.result`에 불리언을 씁니다. 분기를 결정하는 것은 **간선의 조건**이지 노드 자체가 아닙니다 |
| `notify` | 이미 설정한 봇 채널(Telegram / Discord / Slack / DingTalk / Feishu / WeCom)로 렌더링된 메시지를 보냅니다 |
| `approval` | 실행을 일시 중단하고 관리자의 승인 또는 거부를 기다립니다. 타임아웃, 댓글 필수, 변경 불가능한 상세 스냅샷을 지원합니다 |
| `wait` | `durationSec`(1~3600)만큼 기다린 뒤 계속합니다. 500ms마다 취소를 확인하므로 sleep에 갇히지 않습니다 |

**노드 간 통신:** 값은 간선을 따라 세 개의 채널으로 흐릅니다. **스칼라 변수**(`run.input.<name>` / `nodes.<key>.output.<path>` / `outputVar` 별칭. 후속 노드만 볼 수 있고 웨이브 말미에 `workflow_run.variables_json`으로 스냅샷됩니다. 단일 값 상한 8192 UTF-8 바이트, 초과 시 head+tail 프리뷰와 `truncated: true`), **artifact 참조**(*계획 중·미구현*), **secret**(*의도적으로 미구현* — agent 프로토콜이 전달하는 것은 스크립트 본문뿐이라 주입된 secret은 task의 스크립트 열에 쓰이고 `env` 계열 명령으로 출력됩니다). 스크립트 보간은 4중 방어(값 정책 화이트리스트 → `{{NAME}}`의 단일 인용 부호 감싸기 → 치환 구간의 `$(`/`` ` ``/`${` 검사 → 디스패ch 병목의 리스크 게이트)를 거칩니다.

**실패 정책:** `abort`(이후 전부 취소, run은 failed) / `skip`(기본. 해당 노드만 skipped) / `partial`(실패한 상류 출력을 부재로 간주하고 실행). 들어오는 간선이 서로 다르면 **가장 엄격한** 쪽이 이기며, 인식할 수 없는 정책은 `abort`로 처리합니다.

**승인:** `approval` 노드는 **스레드를 점유하지 않은 채** 실행을 멈춥니다. DB의 승인 행이 유일한 진실 원천이고, 결정 후 동일 JVM 이벤트 + 10초 스캐너로 실행이 재개됩니다. 고위험 스크립트를 실행할 수 있는 유일한 정당한 경로는 위상적 조상에 있는 승인된 `approval` 노드입니다. 또한 "취소"는 "기다리지 않는다"는 뜻이며, 이미 전달된 작업은 호스트 측에서 끝까지 실행되고 그 결과는 버려집니다.

**캔버스:** 박스 선택, 정렬 가이드와 스냅, 20px 그리드 스냅, 실행 취소/재실행(Ctrl+Z / Ctrl+Shift+Z), 미니맵, 자동 레이아웃(AI 계획 뷰의 깊이 알고리즘을 재사용, 레이아웃 라이브러리 불필요), 키 재생성 복사/붙여넣기/복제, 키보드 조작(Delete / Ctrl+A / Ctrl+S / 방향키 20px·Shift 4px / Enter / Esc / Space+드래그). **캔버스는 구조의 유일한 편집기이고, 폼은 선택한 노드 설정의 유일한 편집기입니다.**

**미구현(로드맵):** cron / webhook 트리거, `parallel` / `ai-analyze` / `http-request` 노드, dry-run, replay, 템플릿 마켓, artifact 저장, secret 주입, 실행 중 run의 재시작 후 이어달리기, fan-in 변수 병합. 단일 노드 시험 실행 엔드포인트는 서버에 있으나 UI에는 아직 연결되지 않았습니다. 여러 server 인스턴스가 같은 DB를 가리키는 운영은 자기 책임입니다(단일 실행자 전제, 인스턴스 간 잠금 없음). 자세한 내용은 [《Workflow 从 0 到 1》](https://docs.easyshell.ai/guides/workflow/)(영어·간체)를 참고하세요.

---

## 빠른 시작

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 필요시 .env 수정
docker compose up -d
```

로컬 빌드가 필요 없습니다 — 사전 빌드된 이미지가 [Docker Hub](https://hub.docker.com/u/laolupaojiao)에서 자동으로 풀링됩니다.

`http://localhost:18880` 접속 → `easyshell`로 로그인하세요. 첫 시작 시 서버 로그에 무작위로 생성된 초기 비밀번호가 출력됩니다(`EASYSHELL_ADMIN_PASSWORD`로 직접 지정할 수도 있습니다). 로그인 후 즉시 변경하세요.

> **GHCR을 대신 사용하고 싶으신가요?** `.env`에서 설정하세요:
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **개발자이신가요? 소스에서 빌드하기:**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

---

## 전체 기능 세트

| 카테고리 | 기능 |
|----------|----------|
| **AI 지능** | AI 스크립트 어시스턴트(생성 / 수정 / diff / 요약), AI 작업 오케스트레이션(다단계 계획, 병렬 실행, 분석), AI 예약 점검(Cron 기반, AI 출력 분석, 지능형 알림 결정, 멀티채널 봇 푸시), AI 채팅, 점검 보고서, 운영 승인 |
| **운영** | 스크립트 라이브러리, 일괄 실행, 실시간 스트리밍 로그, 파일 관리자가 포함된 웹 SSH 터미널, **워크플로 엔진**(시각적 DAG 캔버스, 노드 간 파라미터 전달, 실패 정책, 사용자 승인, 실행 모니터링) |
| **인프라** | 호스트 관리, 실시간 모니터링, 클러스터 그룹화, 에이전트 자동 배포 |
| **관리** | 사용자 관리, 시스템 구성, AI 모델 설정(OpenAI / Gemini / Copilot / Custom), 위험 제어 |
| **플랫폼** | 다국어 지원(EN / ZH), 다크/라이트 테마, 반응형 디자인, 감사 로깅 |

---

## 아키텍처

```
┌──────────────┐       HTTP/WS        ┌──────────────────┐
│  EasyShell   │◄─────────────────────►│   EasyShell      │
│    Agent     │  register / heartbeat │     Server       │
│  (Go 1.24)  │  script exec / logs   │ (Spring Boot 3.5)│
└──────────────┘                       └────────┬─────────┘
                                                │
                                       ┌────────┴─────────┐
                                       │   EasyShell Web   │
                                       │ (React + Ant Design)│
                                       └──────────────────┘
```

## 기술 스택

| 구성 요소 | 기술 |
|-----------|-----------|
| 서버 | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| 에이전트 | Go 1.24, 단일 바이너리, 런타임 의존성 없음 |
| 웹 | React 19, TypeScript, Vite 7, Ant Design 6 |
| 데이터베이스 | MySQL 8.0 |
| 캐시 | Redis 7 |

## 프로젝트 구조

```
easyshell/
├── easyshell-server/           # 중앙 관리 서버 (Java / Spring Boot)
├── easyshell-agent/            # 에이전트 클라이언트 (Go, 단일 바이너리)
├── easyshell-web/              # 웹 프론트엔드 (React + Ant Design)
├── docker-compose.yml          # 프로덕션 배포 (사전 빌드 이미지 풀링)
├── docker-compose.build.yml    # 개발 환경 (소스에서 로컬 빌드)
├── Dockerfile.server           # 서버 + 에이전트 멀티 스테이지 빌드
├── Dockerfile.web              # 웹 프론트엔드 멀티 스테이지 빌드
├── .github/workflows/          # CI/CD: Docker 이미지 빌드 및 게시
└── .env.example                # 환경 설정 템플릿
```

## 문서

**[docs.easyshell.ai](https://docs.easyshell.ai)**를 방문하여 다음 내용을 확인하세요:

- 설치 및 배포 가이드
- 시작하기 워크스루
- 구성 레퍼런스
- 개발 가이드

## 커뮤니티

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

지식 공유, 토론 및 업데이트를 위해 Discord 커뮤니티에 참여하세요:
**[https://discord.gg/akQqRgNB6t](https://discord.gg/akQqRgNB6t)**

## 라이선스

이 프로젝트는 [MIT 라이선스](./LICENSE)에 따라 라이선스가 부여됩니다.
