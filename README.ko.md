# EasyShell

**경량 서버 관리 & 지능형 운영 플랫폼**

Server-Agent 아키텍처 | 일괄 스크립트 실행 | 실시간 로그 | AI 기반 운영 | 봇 알림

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

**언어**: [English](./README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-TW.md) | 한국어 | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 하이라이트: AI 기반 정기 점검

> **정기 작업 → 스크립트 실행 → AI 지능 분석 → 봇 알림** — 완전 자동화된 서버 점검 파이프라인.

EasyShell은 cron 표현식을 통해 서버에서 **정기 점검 작업**을 실행하고, 스크립트 출력(디스크 사용량, 서비스 건강 상태, 로그 등)을 자동 수집하여 **AI 모델에 지능형 분석**을 요청하고, **봇 채널**을 통해 분석 보고서를 팀에 푸시합니다 — 사람의 개입 없이 완전 자동으로 수행됩니다.

**지원 봇 채널** ([구성 가이드](https://docs.easyshell.ai/configuration/bot-channels/)):

| 봇 | 상태 |
|-----|------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |
| [DingTalk (딩톡)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |
| [Feishu (페이슈)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |
| [WeCom (위컴)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 지원 |

**작동 방식:**
1. **구성** 정기 작업: cron 표현식 + Shell 스크립트 + AI 분석 프롬프트
2. **실행** — EasyShell이 일정에 따라 대상 Agent에 스크립트 배포
3. **분석** — 스크립트 출력이 구성된 AI 모델로 전송 (OpenAI / Gemini / GitHub Copilot / 사용자 정의)
4. **알림** — AI 분석 보고서가 봇 채널로 푸시 (Telegram, Discord, Slack 등)

## 빠른 시작

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 필요시 .env 수정
docker compose up -d
```

로컬 빌드 불필요 — 사전 빌드된 이미지가 [Docker Hub](https://hub.docker.com/u/laolupaojiao)에서 자동으로 풀링됩니다.

`http://localhost:18880` 접속 → `easyshell` / `easyshell@changeme`로 로그인.

> **GHCR을 사용하려면?** `.env`에 설정:
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **개발자용 소스 빌드:**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

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
|-----------|------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, 단일 바이너리, 런타임 의존성 없음 |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| 데이터베이스 | MySQL 8.0 |
| 캐시 | Redis 7 |

## 주요 기능

| 카테고리 | 기능 |
|----------|------|
| **인프라** | 호스트 관리, 모니터링, 클러스터 그룹화 |
| **운영** | 스크립트 라이브러리, 일괄 실행, 실시간 로그, 웹 터미널 |
| **AI 지능** | AI 채팅, **정기 점검 + AI 분석 + 봇 푸시**, 점검 보고서, 운영 승인 |
| **관리** | 사용자 관리, 시스템 설정, AI 모델 구성, 위험 제어, Agent 오케스트레이션 |
| **플랫폼** | 다국어 (EN / ZH), 다크/라이트 테마, 반응형 디자인, 감사 로깅 |

## 스크린샷

### 호스트 관리
![호스트 관리](https://easyshell.ai/images/features/host-management.png)

### 스크립트 실행
![스크립트 실행](https://easyshell.ai/images/features/script-execution.png)

### 실시간 로그
![실시간 로그](https://easyshell.ai/images/features/realtime-logs.png)

### 웹 터미널
![웹 터미널](https://easyshell.ai/images/features/web-terminal.png)

### AI 운영
![AI 운영](https://easyshell.ai/images/features/ai-operations.png)

### 보안 제어
![보안 제어](https://easyshell.ai/images/features/security-controls.png)

### Bot 연동

[Telegram](https://docs.easyshell.ai/configuration/bot-channels/) · [Discord](https://docs.easyshell.ai/configuration/bot-channels/) · [Slack](https://docs.easyshell.ai/configuration/bot-channels/) · [DingTalk](https://docs.easyshell.ai/configuration/bot-channels/) · [Feishu](https://docs.easyshell.ai/configuration/bot-channels/) · [WeCom](https://docs.easyshell.ai/configuration/bot-channels/) 지원 — 대화형 채팅 & 정기 점검 알림 푸시.

![Bot 연동](docs/images/bot-integration.png)

## 프로젝트 구조

```
easyshell/
├── easyshell-server/           # 중앙 관리 서버 (Java / Spring Boot)
├── easyshell-agent/            # Agent 클라이언트 (Go, 단일 바이너리)
├── easyshell-web/              # 웹 프론트엔드 (React + Ant Design)
├── docker-compose.yml          # 프로덕션 배포 (사전 빌드 이미지 풀링)
├── docker-compose.build.yml    # 개발 환경 (소스에서 로컬 빌드)
├── Dockerfile.server           # Server + Agent 멀티스테이지 빌드
├── Dockerfile.web              # 웹 프론트엔드 멀티스테이지 빌드
├── .github/workflows/          # CI/CD: Docker 이미지 빌드 및 배포
└── .env.example                # 환경 변수 설정 템플릿
```

## 문서

**[docs.easyshell.ai](https://docs.easyshell.ai)** 에서 확인하세요:

- 설치 및 배포 가이드
- 시작 안내
- 구성 레퍼런스
- 개발 가이드

## 라이선스

이 프로젝트는 [MIT 라이선스](./LICENSE)에 따라 라이선스가 부여됩니다.
