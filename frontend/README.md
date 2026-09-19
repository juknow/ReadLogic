# ReadLogic Frontend

ReadLogic의 책 등록·목록·상세 편집 화면입니다. 책 메타데이터는 PostgreSQL, 페이지 이미지는 MinIO에 저장되며 프론트엔드는 Spring Boot REST API만 데이터 원본으로 사용합니다.

현재 디렉터리 구조, 각 모듈의 책임, OCR polling과 데이터 lifecycle은 [ARCHITECTURE.md](ARCHITECTURE.md)를 참고합니다.

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

백엔드를 IDE에서 실행하려면 루트에서 `docker compose up -d postgres minio ocr-service`로 인프라와 OCR 서비스를 띄운 뒤 `backend` 디렉터리에서 `./gradlew.bat bootRun`을 실행해도 됩니다.

## 데이터 정책

- 새 책 등록, 내 책 목록, 상세 열람 및 편집은 모두 서버 API를 사용합니다.
- 과거 `readlogic` IndexedDB 데이터는 앱을 처음 실행할 때 삭제하며 서버로 이전하지 않습니다.
- 다른 탭이 IndexedDB를 사용해 삭제가 막히면 앱은 계속 실행하고 다음 실행에서 삭제를 재시도합니다.
- 편집 저장은 제목·저자, 페이지 번호 변경, 이미지 교체, 중간 페이지 추가를 한 요청으로 처리합니다. 실패하면 화면 초안은 유지되고 서버 데이터는 변경되지 않습니다.
- JPEG, PNG, WebP 이미지만 지원하며 한 장의 최대 크기는 10MB입니다.
- 새 이미지의 OCR 상태는 `pending`으로 시작하고 등록 응답은 인식 완료를 기다리지 않습니다.
- 새 책은 기본 OCR 언어를 `ko`, `en`, `ja`, `zh`, `auto` 중에서 선택하며 생략 시 한국어입니다.
- 상세 화면에서 책 기본 언어를 바꾸거나 페이지별 override를 선택해 재인식할 수 있습니다.
- 구조화 OCR의 감지 언어와 fallback warning을 표시하지만 paragraph/bbox 편집 UI는 현재 제공하지 않습니다.
- 상세 화면은 OCR이 대기 또는 처리 중인 동안 2초마다 상태를 갱신합니다. 모든 페이지가 완료 또는 실패 상태가 되면 폴링을 중단합니다.
- 브라우저 탭이 숨겨지거나 책 구조를 편집하는 동안에는 폴링하지 않습니다.
- 완료된 본문은 직접 교정할 수 있으며 실패한 페이지는 상세 화면에서 다시 인식을 요청할 수 있습니다.
- OCR 상태 갱신은 사용자가 입력 중인 본문이나 책 구조 편집 초안을 덮어쓰지 않습니다.

## 검증

```powershell
npm run test
npm run lint
npm run typecheck
npm run build
```
