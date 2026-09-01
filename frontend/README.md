# ReadLogic Frontend

ReadLogic의 책 등록·목록·상세 편집 화면입니다. 책 메타데이터는 PostgreSQL, 페이지 이미지는 MinIO에 저장되며 프론트엔드는 Spring Boot REST API만 데이터 원본으로 사용합니다.

## 로컬 실행

저장소 루트에서 백엔드와 인프라를 먼저 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

백엔드 health 응답이 `UP`인지 확인합니다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

새 터미널에서 프론트엔드를 실행합니다.

```powershell
Set-Location frontend
Copy-Item .env.example .env.local
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173`을 엽니다. 기본 API 주소는 `http://localhost:8080`이며 다른 서버를 사용할 때는 `.env.local`의 `VITE_API_BASE_URL`을 변경합니다.

백엔드를 IDE에서 실행하려면 루트에서 `docker compose up -d postgres minio`로 인프라만 띄운 뒤 `backend` 디렉터리에서 `./gradlew.bat bootRun`을 실행해도 됩니다.

## 데이터 정책

- 새 책 등록, 내 책 목록, 상세 열람 및 편집은 모두 서버 API를 사용합니다.
- 과거 `readlogic` IndexedDB 데이터는 앱을 처음 실행할 때 삭제하며 서버로 이전하지 않습니다.
- 다른 탭이 IndexedDB를 사용해 삭제가 막히면 앱은 계속 실행하고 다음 실행에서 삭제를 재시도합니다.
- 편집 저장은 제목·저자, 페이지 번호 변경, 이미지 교체, 중간 페이지 추가를 한 요청으로 처리합니다. 실패하면 화면 초안은 유지되고 서버 데이터는 변경되지 않습니다.
- JPEG, PNG, WebP 이미지만 지원하며 한 장의 최대 크기는 10MB입니다.
- 실제 OCR 처리는 아직 포함되지 않아 새 이미지의 상태는 `pending`으로 시작합니다.

## 검증

```powershell
npm run test
npm run lint
npm run typecheck
npm run build
```
