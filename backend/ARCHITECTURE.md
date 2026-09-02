# Backend Architecture

이 문서는 `backend`의 현재 Spring Boot 구현, package 책임과 OCR 작업 정합성을 설명한다. 실행·요청 예시는 [README.md](README.md), 제품 범위는 루트의 [READLOGIC_PROJECT_CONTEXT.md](../READLOGIC_PROJECT_CONTEXT.md)를 따른다.

## 1. 책임 경계

백엔드는 프론트엔드가 호출하는 공개 API와 영속 상태의 소유자다.

```text
React frontend
   ↓ public /api/books
Spring Boot backend
   ├─ PostgreSQL: book, page, OCR 상태와 JSONB document
   ├─ MinIO: 원본 page image
   └─ Python OCR: internal recognition request
```

- 프론트엔드는 PostgreSQL, MinIO, OCR 서비스에 직접 접근하지 않는다.
- Python OCR 서비스는 book/page ID와 저장소를 알지 못한다.
- 백엔드는 OCR 작업 선점, retry, stale job 복구, language 전달과 revision 검사를 담당한다.
- 이미지 binary는 PostgreSQL에 저장하지 않고 MinIO object key만 보관한다.

## 2. 디렉터리 구조

`build`와 Gradle cache 같은 생성물은 제외했다.

```text
backend/src/
├─ main/
│  ├─ java/com/readlogic/backend/
│  │  ├─ BackendApplication.java
│  │  ├─ book/
│  │  │  ├─ api/
│  │  │  │  ├─ BookController.java
│  │  │  │  ├─ AddBookPageRequest.java
│  │  │  │  ├─ BookPageResponse.java
│  │  │  │  ├─ BookResponse.java
│  │  │  │  ├─ BookSummaryResponse.java
│  │  │  │  ├─ CreateBookRequest.java
│  │  │  │  ├─ ReplaceBookRequest.java
│  │  │  │  ├─ RequestPageOcrRequest.java
│  │  │  │  ├─ UpdateBookPageRequest.java
│  │  │  │  ├─ UpdateBookRequest.java
│  │  │  │  ├─ ImageUploadProperties.java
│  │  │  │  └─ ImageUploadValidator.java
│  │  │  ├─ application/BookApplicationService.java
│  │  │  └─ domain/
│  │  │     ├─ Book.java
│  │  │     ├─ BookPage.java
│  │  │     ├─ BookRepository.java
│  │  │     ├─ BookPageRepository.java
│  │  │     ├─ OcrLanguage.java
│  │  │     ├─ OcrStatus.java
│  │  │     └─ TextSource.java
│  │  ├─ ocr/
│  │  │  ├─ application/
│  │  │  │  ├─ OcrClient.java
│  │  │  │  ├─ OcrClientException.java
│  │  │  │  ├─ OcrImage.java
│  │  │  │  ├─ OcrJob.java
│  │  │  │  ├─ OcrJobCoordinator.java
│  │  │  │  ├─ OcrJobProcessor.java
│  │  │  │  └─ OcrResult.java
│  │  │  └─ infrastructure/
│  │  │     ├─ HttpOcrClient.java
│  │  │     ├─ OcrJobDispatcher.java
│  │  │     ├─ OcrProperties.java
│  │  │     └─ OcrWorkerConfiguration.java
│  │  ├─ storage/
│  │  │  ├─ PageImageStorage.java
│  │  │  ├─ MinioPageImageStorage.java
│  │  │  ├─ MinioConfiguration.java
│  │  │  ├─ MinioProperties.java
│  │  │  └─ ImageStorageException.java
│  │  ├─ common/error/
│  │  │  ├─ ApiErrorResponse.java
│  │  │  ├─ GlobalExceptionHandler.java
│  │  │  ├─ DuplicateResourceException.java
│  │  │  ├─ InvalidRequestException.java
│  │  │  └─ ResourceNotFoundException.java
│  │  └─ config/
│  │     ├─ CorsProperties.java
│  │     └─ WebConfiguration.java
│  └─ resources/
│     ├─ application.yml
│     └─ db/migration/
│        ├─ V1__create_books_and_book_pages.sql
│        ├─ V2__add_ocr_processing_fields.sql
│        └─ V3__add_structured_multilingual_ocr.sql
└─ test/
   ├─ java/com/readlogic/backend/
   │  ├─ book/*Tests.java
   │  ├─ ocr/*Tests.java
   │  ├─ database/PostgresMigrationContainerTests.java
   │  └─ storage/MinioPageImageStorageContainerTests.java
   └─ resources/application.yml
```

## 3. 계층별 역할

### `book/api`

- HTTP method/path, multipart parsing, validation과 response DTO를 담당한다.
- `BookController`는 application service를 호출하고 domain 로직을 직접 구현하지 않는다.
- `ImageUploadValidator`는 허용 MIME과 크기를 공개 API 경계에서 검사한다.
- `BookPageResponse`는 detail에서 `ocrDocument`를 제공하고 summary projection에서는 document 값을 비운다.

### `book/application`

- `BookApplicationService`가 책 aggregate use case와 transaction boundary를 담당한다.
- create/replace 시 metadata와 image part의 개수·순서를 검증한다.
- MinIO 저장과 DB 저장 사이 rollback/after-commit 보상 정리를 등록한다.
- 페이지 수동 교정, 이미지 교체와 OCR 재요청의 document lifecycle을 시작한다.

### `book/domain`

- `Book`: 책 metadata, 기본 OCR 언어와 page aggregate를 소유한다.
- `BookPage`: 페이지 metadata, OCR revision/state, text source, language override와 structured document를 소유한다.
- repository interface는 JPA persistence와 OCR 선점용 query를 제공한다.
- enum은 DB에는 대문자, API에는 소문자 값으로 변환한다.

### `ocr/application`

- `OcrJobCoordinator`: 짧은 DB transaction 안에서 선점, 완료, retry와 stale 복구를 수행한다.
- `OcrJobProcessor`: MinIO image를 읽고 OCR client를 호출한 뒤 결과/오류를 coordinator에 전달한다.
- `OcrClient`: Python transport의 application-level port다.
- `OcrJob`: 선점 시점의 page ID, revision, attempt, object key, MIME, effective language snapshot이다.

### `ocr/infrastructure`

- `HttpOcrClient`: multipart image/language와 `X-Ocr-Request-Id`를 Python API로 전송한다.
- `OcrJobDispatcher`: scheduler에서 가용 concurrency만큼 작업을 선점해 executor로 보낸다.
- `OcrWorkerConfiguration`: scheduler와 고정 크기 OCR executor를 구성한다.
- `OcrProperties`: base URL, timeout, attempts, polling, stale 기준, concurrency와 batch size를 검증한다.

### `storage`

- `PageImageStorage`는 application이 의존하는 저장소 abstraction이다.
- `MinioPageImageStorage`가 object key 생성, bucket 준비, 저장/조회/삭제를 구현한다.
- 저장 오류는 `ImageStorageException`으로 변환한다.

## 4. 공개 요청 흐름

```text
BookController
  ↓ request validation / DTO conversion
BookApplicationService (@Transactional)
  ↓ aggregate rule
Book / BookPage
  ↓
JPA Repository → PostgreSQL
  ↘ PageImageStorage → MinIO
```

책 생성과 일괄 수정은 metadata와 image의 위치 관계를 `imageIndex`로 명시한다. 일괄 수정은 최종 page 목록을 전체 전달해야 하며, 기존 page가 빠지거나 image index가 중복되면 적용하지 않는다.

## 5. OCR 비동기 흐름

```text
new/replaced/retried page = PENDING
       ↓ scheduler (기본 2초)
SELECT ... FOR UPDATE SKIP LOCKED
       ↓ claim + revision/language snapshot
PROCESSING
       ↓ worker outside long DB transaction
MinIO load → HttpOcrClient → Python OCR
       ↓
conditional UPDATE WHERE status=PROCESSING AND revision=snapshot
       ├─ 1 row: READY + text + JSONB
       └─ 0 row: stale result 폐기
```

네트워크/추론 중 DB row lock을 유지하지 않는다. 작업 선점과 최종 반영만 각각 짧은 transaction이다.

## 6. OCR 상태 전이

```text
                         ┌──────── retryable failure (< max attempts) ────────┐
                         │                                                    │
PENDING ── claim ──> PROCESSING ── success ──> READY                          │
                         ├─ permanent/max failure ──> FAILED                  │
                         └─ stale recovery ──────────> PENDING ───────────────┘

READY / FAILED ── retry or image change ──> PENDING
PROCESSING ── manual edit / image change ──> READY or PENDING + revision 증가
```

- 첫 retry delay는 5초, 그다음은 30초다.
- 기본 최대 attempt는 3회다.
- 기본 5분 이상 `processing`이면 scheduler가 `pending`으로 복구한다.
- `OCR_ENABLED=false`이면 새 작업은 `pending`에 남고 책 API는 계속 동작한다.

## 7. Revision 정합성

`BookPage.ocrRevision`은 이미지, 재인식 조건 또는 수동 본문이 바뀔 때 증가한다. 선점한 `OcrJob`은 revision을 snapshot으로 보관한다.

완료 update 조건:

```text
page.id 일치
AND ocr_status = PROCESSING
AND ocr_revision = job.revision
```

사용자가 처리 중 본문을 고치거나 이미지를 바꾸면 이전 OCR 응답은 조건을 만족하지 못해 text와 document 모두 폐기된다.

## 8. 언어 우선순위

```text
BookPage.ocrLanguage != null
  └─ page override 사용
BookPage.ocrLanguage == null
  └─ Book.defaultOcrLanguage 사용
```

허용 값은 `auto`, `ko`, `en`, `ja`, `zh`다. 기존 책은 migration에서 `KO`로 backfill되며 새 필드를 보내지 않는 생성 요청도 `ko`다. body 없는 기존 OCR retry는 현재 override를 유지한다. `{"language": null}`은 override를 제거한다.

## 9. Structured OCR JSONB lifecycle

`book_pages.ocr_document`는 versioned OCR document를 JSONB로 저장한다.

- OCR 성공: text/confidence/model과 document를 함께 저장
- OCR 실패/retry 대기: 이전 document 제거
- 이미지 교체: document 제거, `pending`
- 언어 변경: document 제거, `pending`
- 수동 text 교정: document 제거, `textSource=manual`, `ready`
- 기존 migration row: document `null`

별도 paragraph/line relational table은 현재 없다. JSON 구조는 `schemaVersion=1`이며 backend는 라이브러리 전용 JSON node 대신 `Map<String, Object>`로 보관해 HTTP/JPA 직렬화 계층을 분리한다.

## 10. Database와 Flyway

| migration | 역할 |
| --- | --- |
| `V1` | `books`, `book_pages`와 기본 metadata |
| `V2` | OCR status, revision, attempts, error와 timing field |
| `V3` | 책 기본 언어, page override, structured OCR JSONB |

- schema 변경은 새 versioned migration으로만 추가한다.
- 이미 적용된 migration을 수정하지 않는다.
- Hibernate `ddl-auto=validate`는 schema를 생성하지 않고 검증만 한다.
- production 대상은 PostgreSQL이며 migration은 Testcontainers PostgreSQL에서도 검증한다.

## 11. MinIO와 transaction 보상

DB와 object storage는 하나의 XA transaction이 아니다. application service는 Spring transaction synchronization으로 보상한다.

- DB commit 전 실패: 요청 중 새로 저장한 object 삭제
- 교체/삭제 성공: DB commit 이후 이전 object 삭제
- cleanup 실패: 원래 DB 결과는 유지하고 오류를 log로 남김

object key를 domain 외부에서 임의로 조합하지 않고 `PageImageStorage` 구현에 맡긴다.

## 12. API contract

공개 base path는 `/api/books`다. 전체 표와 예시는 README에 있다.

OCR 관련 additive field:

- `BookResponse.defaultOcrLanguage`
- `BookPageResponse.ocrLanguage`
- `BookPageResponse.ocrDocument`
- 기존 `extractedText`, status, confidence, engine, model, error, timing, source 유지

내부 OCR 요청:

```text
POST /internal/v1/ocr
X-Ocr-Request-Id: page UUID
multipart image=<binary>, language=<effective language>
```

Python의 4xx는 permanent, 429/5xx/연결 실패는 retryable로 분류한다. `OCR_TIMEOUT` 504도 retryable이다.

## 13. Configuration

source of truth는 `src/main/resources/application.yml`과 typed properties다.

- DB: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- MinIO: `MINIO_ENDPOINT`, access/secret key, bucket
- upload: Spring multipart limit와 application byte limit
- CORS: `CORS_ALLOWED_ORIGINS`
- OCR: enabled, base URL, connect/read timeout, attempts, polling, stale, concurrency, batch size

기본 OCR concurrency는 backend와 Python 모두 1이며 CPU oversubscription과 Paddle model 중복 사용을 피한다.

## 14. 테스트 책임

| 테스트 | 책임 |
| --- | --- |
| `BookApiIntegrationTests` | 공개 API, multipart, 언어, page lifecycle |
| `BookApplicationServiceTests` | aggregate transaction과 MinIO 보상 |
| `ImageUploadValidatorTests` | MIME/byte 제한 |
| `HttpOcrClientTests` | internal multipart, language와 응답/오류 mapping |
| `OcrJobCoordinatorTests` | 선점, retry, stale 복구, revision, JSONB와 language snapshot |
| `PostgresMigrationContainerTests` | 실제 PostgreSQL Flyway 적용 |
| `MinioPageImageStorageContainerTests` | 실제 MinIO 저장/조회/삭제 |

전체 검증은 `./gradlew test`다. Docker가 없는 환경에서는 Testcontainers test가 실행되지 않을 수 있으므로 결과를 별도로 명시한다.

## 15. 확장 위치

- 새 책 use case: `book/application`, 불변식은 `book/domain`, HTTP DTO는 `book/api`
- 새 외부 OCR transport: `OcrClient` port를 유지하고 `ocr/infrastructure`에 구현
- 새 image provider: `PageImageStorage` port를 구현
- OCR document schema 변경: Python `schemaVersion`과 backend/frontend compatibility를 함께 검토
- worker 정책 변경: scheduler, coordinator transaction과 processor I/O 책임을 섞지 않음
- package 책임, endpoint, schema 또는 상태 전이가 바뀌면 이 문서를 같은 PR에서 갱신
