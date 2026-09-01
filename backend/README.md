# ReadLogic Backend

ReadLogic의 책·페이지 데이터와 OCR 작업 상태를 관리하는 Spring Boot API입니다. PostgreSQL에는 책 메타데이터와 OCR 텍스트를, MinIO에는 원본 페이지 이미지를 저장합니다. 페이지 인식은 내부 Python OCR 서비스에 위임하지만 작업 선점, 재시도와 결과 정합성은 이 백엔드가 책임집니다.

## 요구 사항

- Java 21
- Python OCR 서비스를 직접 실행할 때는 Python 3.11과 uv
- Docker와 Docker Compose

## 실행 방법

저장소 루트에서 환경변수 파일을 준비합니다.

```powershell
Copy-Item .env.example .env
```

전체 환경을 Docker로 실행합니다.

```shell
docker compose up --build
```

- Backend API: `http://localhost:8080`
- Health check: `http://localhost:8080/actuator/health`
- MinIO Console: `http://localhost:9001`
- PostgreSQL: `localhost:5432`

첫 실행에는 PaddleOCR 모델을 내려받아야 하므로 OCR readiness가 준비될 때까지 시간이 걸릴 수 있습니다. 모델은 `ocr-model-cache` named volume에 보관되어 다음 실행부터 재사용됩니다.

IDE에서 백엔드를 실행하려면 인프라와 OCR 서비스만 먼저 실행할 수 있습니다.

```shell
docker compose up -d postgres minio ocr-service
```

그다음 `backend` 디렉터리에서 실행합니다.

```powershell
.\gradlew.bat bootRun
```

Flyway가 시작 시 `src/main/resources/db/migration`의 스키마를 자동 적용하며 Hibernate는 결과 스키마만 검증합니다.

## API

모든 책 API의 기본 경로는 `/api/books`입니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/books` | 책과 여러 페이지 이미지 등록 |
| `GET` | `/api/books` | 책 목록 조회 |
| `GET` | `/api/books/{bookId}` | 책과 페이지 상세 조회 |
| `PUT` | `/api/books/{bookId}` | 책 정보와 페이지 변경사항 일괄 저장 |
| `PATCH` | `/api/books/{bookId}` | 제목과 저자 수정 |
| `DELETE` | `/api/books/{bookId}` | 책과 관련 이미지 삭제 |
| `POST` | `/api/books/{bookId}/pages` | 페이지 추가 |
| `PATCH` | `/api/books/{bookId}/pages/{pageId}` | 페이지 번호 또는 추출 텍스트 수정 |
| `PUT` | `/api/books/{bookId}/pages/{pageId}/image` | 페이지 이미지 교체 |
| `DELETE` | `/api/books/{bookId}/pages/{pageId}` | 페이지 삭제 |
| `GET` | `/api/books/{bookId}/pages/{pageId}/image` | 페이지 이미지 조회 |
| `POST` | `/api/books/{bookId}/pages/{pageId}/ocr` | 페이지 OCR 재인식 요청 |

책 등록은 `metadata` JSON part와 같은 순서의 `images` file part를 사용합니다.

```shell
curl -X POST http://localhost:8080/api/books \
  -F 'metadata={"title":"논리적으로 읽기","author":"홍길동","pages":[{"pageNumber":1},{"pageNumber":2}]};type=application/json' \
  -F 'images=@page-1.png;type=image/png' \
  -F 'images=@page-2.png;type=image/png'
```

책 일괄 수정은 최종 페이지 목록을 `metadata`에 보내고 추가·교체할 이미지만 `images`에 보냅니다. 기존 페이지 ID는 모두 포함해야 하며 `imageIndex`는 `images` 순서의 0부터 시작하는 인덱스입니다.

```shell
curl -X PUT http://localhost:8080/api/books/{bookId} \
  -F 'metadata={"title":"논리적으로 읽기","author":"홍길동","pages":[{"id":"기존-페이지-UUID","pageNumber":2,"imageIndex":0},{"id":null,"pageNumber":1,"imageIndex":1}]};type=application/json' \
  -F 'images=@replacement.png;type=image/png' \
  -F 'images=@new-page.png;type=image/png'
```

요청의 제목·페이지 번호·추가·교체는 하나의 트랜잭션으로 처리됩니다. 검증 또는 이미지 저장에 실패하면 DB 변경을 롤백하고 요청 중 저장한 객체도 정리합니다.

페이지 한 장을 추가할 때는 `metadata`와 `image`를 사용합니다.

```shell
curl -X POST http://localhost:8080/api/books/{bookId}/pages \
  -F 'metadata={"pageNumber":3};type=application/json' \
  -F 'image=@page-3.png;type=image/png'
```

지원 이미지 형식은 JPEG, PNG, WebP이며 기본 한 장 제한은 10MB입니다. 새 이미지의 OCR 상태는 `pending`, 추출 텍스트는 빈 문자열로 생성됩니다. 등록 API는 OCR 완료를 기다리지 않습니다.

페이지 응답에는 다음 OCR 정보가 포함됩니다.

```json
{
  "ocrStatus": "processing",
  "ocrConfidence": null,
  "ocrEngine": null,
  "ocrModel": null,
  "ocrErrorCode": null,
  "ocrErrorMessage": null,
  "ocrRequestedAt": "2026-09-01T00:00:00Z",
  "ocrCompletedAt": null,
  "textSource": "none"
}
```

상태는 `pending`, `processing`, `ready`, `failed` 중 하나입니다. 실패하거나 완료된 페이지를 다시 인식하려면 다음 요청을 보냅니다. 이미 대기 또는 처리 중인 페이지에는 중복 작업을 만들지 않습니다.

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/books/{bookId}/pages/{pageId}/ocr
```

OCR 본문을 직접 교정하면 `textSource`가 `manual`로 바뀌며 빈 문자열도 유효한 수동 결과입니다. 진행 중이던 이전 OCR 결과는 revision 검사에서 폐기됩니다.

```powershell
Invoke-RestMethod `
  -Method Patch `
  -ContentType 'application/json' `
  -Uri http://localhost:8080/api/books/{bookId}/pages/{pageId} `
  -Body '{"extractedText":"사용자가 교정한 본문"}'
```

작업 처리기는 PostgreSQL에서 2초마다 대상을 조회하고 `FOR UPDATE SKIP LOCKED`로 선점합니다. 일시적 오류는 기본 5초, 30초 후 재시도하며 세 번째 실패에서 `failed`가 됩니다. 5분 이상 `processing`인 작업은 재시작 후 자동으로 복구됩니다. `OCR_ENABLED=false`이면 책 API는 그대로 동작하고 OCR 작업만 `pending`에 남습니다.

오류 응답은 다음 구조를 사용합니다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "요청 값을 확인해 주세요.",
  "fieldErrors": {
    "title": "공백일 수 없습니다"
  },
  "timestamp": "2026-09-01T00:00:00Z"
}
```

## 환경변수

주요 값과 로컬 기본값은 저장소 루트의 `.env.example`에 있습니다.

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: IDE에서 백엔드를 직접 실행할 때 사용할 DB 접속 정보
- `POSTGRES_*`: Docker PostgreSQL 설정
- `MINIO_*`: MinIO 접속 정보와 bucket 이름
- `CORS_ALLOWED_ORIGINS`: 허용할 프론트엔드 origin. 여러 값은 쉼표로 구분
- `MAX_IMAGE_SIZE`, `MAX_REQUEST_SIZE`: Spring multipart 제한
- `MAX_IMAGE_SIZE_BYTES`: 애플리케이션의 이미지 한 장 검증 제한
- `OCR_ENABLED`, `OCR_BASE_URL`: OCR 처리 활성화 여부와 내부 서비스 주소
- `OCR_CONNECT_TIMEOUT`, `OCR_READ_TIMEOUT`: OCR 연결 및 추론 응답 제한 시간
- `OCR_MAX_ATTEMPTS`, `OCR_POLL_INTERVAL`, `OCR_STALE_AFTER`: 재시도와 작업 복구 설정
- `OCR_CONCURRENCY`, `OCR_BATCH_SIZE`: 동시 요청 수와 한 번에 선점할 최대 작업 수

실제 비밀번호가 들어가는 `.env`는 Git에 커밋하지 않습니다.

## 테스트

```powershell
.\gradlew.bat test
```

API와 서비스 테스트는 H2 PostgreSQL 호환 모드 및 mock 이미지 저장소로 실행됩니다. Docker를 사용할 수 있는 환경에서는 Testcontainers 테스트가 PostgreSQL Flyway 마이그레이션과 MinIO 저장·조회·삭제도 추가로 검증합니다.

OCR 서비스가 중지되어도 책 등록·목록·상세 API는 정상 응답합니다. 장애 확인 시 페이지의 `ocrErrorCode`, backend 로그의 request ID, OCR 서비스 로그를 함께 확인합니다.
