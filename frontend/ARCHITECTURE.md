# Frontend Architecture

이 문서는 `frontend`의 현재 구현 구조와 책임 경계를 설명한다. 실행 방법은 [README.md](README.md), 제품 범위와 원칙은 루트의 [READLOGIC_PROJECT_CONTEXT.md](../READLOGIC_PROJECT_CONTEXT.md)를 기준으로 한다.

## 1. 책임 경계

프론트엔드는 React 화면, 사용자 입력 초안, 서버 API 호출과 응답 표시를 담당한다. 책과 페이지의 영속 데이터 원본은 Spring Boot API이며 브라우저 저장소나 Python OCR 서비스는 원본이 아니다.

```text
Browser
  └─ React frontend
       └─ HTTP /api/*
            └─ Spring Boot backend
                 ├─ PostgreSQL
                 ├─ MinIO
                 └─ internal OCR service
```

- 프론트엔드는 OCR Python endpoint를 직접 호출하지 않는다.
- OCR 작업 선점, 재시도, timeout과 revision 정합성은 백엔드 책임이다.
- 프론트엔드는 `pending`/`processing` 상태를 조회하고 결과를 표시한다.
- 서버 OCR 원문은 사용자가 직접 교정할 수 있지만, 구조화 OCR 좌표 자체를 편집하는 UI는 현재 없다.

## 2. 디렉터리 구조

`node_modules`, `dist`, TypeScript build info 같은 생성물은 제외했다.

```text
frontend/
├─ src/
│  ├─ app/
│  │  ├─ errors/
│  │  │  ├─ NotFoundPage.tsx
│  │  │  ├─ RouteErrorPage.tsx
│  │  │  └─ RouteState.module.css
│  │  ├─ layouts/
│  │  │  ├─ components/AppHeader.tsx
│  │  │  ├─ components/AppHeader.module.css
│  │  │  ├─ AppLayout.tsx
│  │  │  └─ AppLayout.module.css
│  │  ├─ router/
│  │  │  ├─ paths.ts
│  │  │  └─ router.tsx
│  │  └─ App.tsx
│  ├─ features/
│  │  └─ books/
│  │     ├─ data/
│  │     │  ├─ bookApiRepository.ts
│  │     │  ├─ bookApiRepository.test.ts
│  │     │  ├─ clearLegacyBookStorage.ts
│  │     │  └─ clearLegacyBookStorage.test.ts
│  │     ├─ hooks/useObjectUrl.ts
│  │     └─ model/
│  │        ├─ book.ts
│  │        └─ bookImage.ts
│  ├─ pages/
│  │  ├─ home/
│  │  │  ├─ components/
│  │  │  │  ├─ HeroSection.tsx
│  │  │  │  ├─ HeroSection.module.css
│  │  │  │  ├─ TrainingFlowSection.tsx
│  │  │  │  ├─ TrainingFlowSection.module.css
│  │  │  │  ├─ TrainingStep.tsx
│  │  │  │  └─ TrainingStep.module.css
│  │  │  ├─ HomePage.tsx
│  │  │  └─ HomePage.module.css
│  │  └─ books/
│  │     ├─ detail/
│  │     │  ├─ BookDetailPage.tsx
│  │     │  ├─ BookDetailPage.module.css
│  │     │  └─ BookDetailPage.test.tsx
│  │     ├─ list/
│  │     │  ├─ BooksPage.tsx
│  │     │  └─ BooksPage.module.css
│  │     └─ new/
│  │        ├─ NewBookPage.tsx
│  │        └─ NewBookPage.module.css
│  ├─ shared/
│  │  ├─ api/
│  │  │  ├─ apiClient.ts
│  │  │  └─ apiClient.test.ts
│  │  └─ styles/
│  │     ├─ globals.css
│  │     ├─ index.css
│  │     ├─ reset.css
│  │     └─ tokens.css
│  └─ main.tsx
├─ .env.example
├─ index.html
├─ package.json
├─ tsconfig*.json
└─ vite.config.ts
```

## 3. 상위 디렉터리 역할

| 디렉터리 | 역할 | 배치하지 않는 것 |
| --- | --- | --- |
| `app` | 애플리케이션 진입점, route, 공통 layout, route 오류 | 책 API 세부 로직 |
| `features/books` | 책 domain type, 서버 adapter, 이미지 규칙, 재사용 hook | route 전용 큰 화면 구성 |
| `pages` | URL 단위 화면과 화면 안의 상호작용 조합 | 공통 HTTP 세부 구현 |
| `shared/api` | base URL, JSON 처리, 공통 오류 변환 | 책 전용 request/response type |
| `shared/styles` | reset, 전역 스타일, 디자인 token | 페이지 전용 layout |

## 4. 주요 파일

### 앱과 route

- `src/main.tsx`: React root를 만들고 애플리케이션을 mount한다.
- `src/app/App.tsx`: legacy IndexedDB 정리를 시작하고 router를 제공한다.
- `src/app/router/paths.ts`: URL 생성 함수를 한 곳에서 관리한다.
- `src/app/router/router.tsx`: Home, 책 목록, 신규 등록, 상세 route와 error element를 연결한다.
- `src/app/layouts/AppLayout.tsx`: 공통 header와 route outlet을 배치한다.
- `src/app/errors/*`: 404와 route-level 오류 상태를 표시한다.

### 책 feature

- `model/book.ts`: `Book`, `BookPage`, OCR 상태, 언어, line/paragraph/document 타입과 언어 표시명을 정의한다.
- `model/bookImage.ts`: JPEG/PNG/WebP, 최대 크기 등 client-side 이미지 검증을 담당한다.
- `data/bookApiRepository.ts`: backend DTO를 화면 model로 바꾸고 multipart/JSON 요청을 만든다.
- `data/clearLegacyBookStorage.ts`: 과거 IndexedDB 저장소를 삭제한다. 현재 책 데이터 저장에는 사용하지 않는다.
- `hooks/useObjectUrl.ts`: 신규·교체 이미지의 임시 object URL 생성과 해제를 담당한다.

### 책 화면

- `NewBookPage.tsx`: 제목, 저자, 기본 OCR 언어와 여러 페이지 이미지를 등록한다.
- `BooksPage.tsx`: 서버 책 목록과 대표 페이지를 표시한다.
- `BookDetailPage.tsx`: 책/페이지 일괄 편집, OCR polling, 페이지 언어 override, 본문 교정과 재인식을 담당한다.

## 5. 데이터 모델 관계

```text
Book
├─ defaultOcrLanguage: ko | en | ja | zh | auto
└─ pages: BookPage[]
    ├─ ocrLanguage: page override | null
    ├─ extractedText: 현재 사용자에게 보여주는 본문
    ├─ textSource: none | ocr | manual
    └─ ocrDocument: OcrDocument | null
        ├─ requestedLanguage / detectedLanguage
        ├─ correction / models / warnings
        └─ paragraphs[]
            └─ lines[]
                ├─ bbox / polygon
                ├─ detectionConfidence / confidence
                └─ language / model / text
```

`extractedText`는 현재 편집 화면의 source of truth다. `ocrDocument`는 OCR 직후의 좌표 기반 결과이며 사용자가 본문을 수정하면 백엔드가 이를 `null`로 만든다. 수정된 text와 과거 OCR 구조가 서로 다른 상태로 남지 않게 하기 위한 정책이다.

## 6. API client와 repository

`shared/api/apiClient.ts`는 다음 공통 처리를 한다.

- `VITE_API_BASE_URL` 또는 로컬 기본 URL 사용
- JSON response parsing
- backend 오류 body를 `ApiError`로 변환
- 상대 이미지 URL을 API base URL에 결합

`bookApiRepository.ts`는 책 API 전용 adapter다.

- 생성/일괄 수정: metadata JSON `Blob`과 image `File`을 `FormData`로 전송
- 목록/상세: backend response를 `Book`/`BookSummary`로 mapping
- 재인식: `language`가 `undefined`이면 기존 body 없는 POST, `null`이면 page override 제거, 값이 있으면 override 설정
- legacy 응답: `defaultOcrLanguage` 누락 시 `ko`, page의 새 OCR field 누락 시 `null`

프론트 model과 backend wire DTO를 분리해 additive API 변경과 URL 변환을 화면 밖에서 처리한다.

## 7. 주요 데이터 흐름

### 새 책 등록

```text
사용자 입력
  ├─ title / author
  ├─ defaultOcrLanguage (기본 ko)
  └─ ordered page images + page numbers
        ↓ client validation
FormData(metadata, images[])
        ↓ POST /api/books
Book response
        ↓
/books/:bookId 이동
```

등록 응답은 OCR 완료를 기다리지 않는다. 각 페이지는 `pending`에서 시작한다.

### 상세 화면 polling

```text
GET book detail
  ↓
pending/processing page 존재?
  ├─ 아니오: polling 없음
  └─ 예: visible tab + non-editing 상태에서 2초 polling
       ↓
     완료/실패만 남으면 중단
```

- 탭이 숨겨지면 polling을 중단한다.
- 책 구조 편집 중에는 polling을 중단한다.
- OCR 본문 textarea가 열려 있으면 서버 refresh가 해당 component의 `textDraft`를 덮어쓰지 않는다.
- polling 실패는 기존 화면을 유지하고 다음 interval에 재시도한다.

### 언어 선택

- 책 생성 시 기본값은 `ko`다.
- 책 편집 저장 시 `defaultOcrLanguage`를 일괄 수정 metadata에 포함한다.
- 페이지 selector의 “책 기본 설정 사용”은 retry body에 `language: null`을 보내 override를 제거한다.
- `ko/en/ja/zh/auto` 선택 시 해당 값을 page override로 저장하고 재인식한다.
- 결과의 `detectedLanguage`와 warning은 읽기 전용 안내로 표시한다.

## 8. 오류와 초안 보존

- route load 오류는 상세 화면 대신 복구 링크가 있는 상태를 표시한다.
- 책 일괄 저장 실패 시 `draftBook`을 유지하고 서버 상태를 덮어쓰지 않는다.
- 본문 저장 실패 시 textarea와 입력값을 유지한다.
- OCR retry 실패 시 page card 안에 오류를 표시하고 기존 OCR 결과를 보존한다.
- 파일 검증은 서버 요청 전에 수행하지만 서버 검증을 대체하지 않는다.

## 9. 테스트 구조

| 테스트 | 책임 |
| --- | --- |
| `shared/api/apiClient.test.ts` | base URL, JSON, 공통 오류 mapping |
| `bookApiRepository.test.ts` | multipart metadata, response mapping, 언어 retry body |
| `BookDetailPage.test.tsx` | polling, tab/edit 중단, draft 보존, language override, warning, manual text |
| `clearLegacyBookStorage.test.ts` | 과거 IndexedDB 삭제와 실패 격리 |

검증 명령은 `npm run test`, `npm run lint`, `npm run typecheck`, `npm run build`다.

## 10. 확장 규칙

- 새 route 화면은 `pages/<domain>/<screen>`에 둔다.
- 둘 이상의 화면이 공유하는 책 로직은 `features/books`로 올린다.
- 책 API wire 변화는 `bookApiRepository.ts`에서 흡수하고 page component가 raw response를 직접 다루지 않게 한다.
- 전역적으로 재사용되는 HTTP/스타일만 `shared`에 둔다.
- OCR paragraph 편집이나 overlay를 추가할 때 `extractedText`와 `ocrDocument`의 lifecycle을 먼저 정의한다.
- route, API, OCR polling 구조가 바뀌면 이 문서를 같은 PR에서 갱신한다.
