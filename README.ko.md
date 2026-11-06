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
| **운영** | 스크립트 라이브러리, 일괄 실행, 실시간 스트리밍 로그, 파일 관리자가 포함된 웹 SSH 터미널 |
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
