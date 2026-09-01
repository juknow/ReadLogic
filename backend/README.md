# ReadLogic Backend

ReadLogic의 책·페이지 데이터를 관리하는 Spring Boot API입니다. PostgreSQL에는 책 메타데이터와 OCR 텍스트를, MinIO에는 원본 페이지 이미지를 저장합니다. 현재 인증과 실제 OCR 처리는 포함하지 않습니다.

## 요구 사항

- Java 21
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

IDE에서 백엔드를 실행하려면 인프라만 먼저 실행할 수 있습니다.

```shell
docker compose up -d postgres minio
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

지원 이미지 형식은 JPEG, PNG, WebP이며 기본 한 장 제한은 10MB입니다. 새 이미지의 OCR 상태는 `pending`, 추출 텍스트는 빈 문자열로 생성됩니다.

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

실제 비밀번호가 들어가는 `.env`는 Git에 커밋하지 않습니다.

## 테스트

```powershell
.\gradlew.bat test
```

API와 서비스 테스트는 H2 PostgreSQL 호환 모드 및 mock 이미지 저장소로 실행됩니다. Docker를 사용할 수 있는 환경에서는 Testcontainers 테스트가 PostgreSQL Flyway 마이그레이션과 MinIO 저장·조회·삭제도 추가로 검증합니다.
