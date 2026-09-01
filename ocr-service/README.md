# ReadLogic OCR Service

ReadLogic의 무상태 내부 페이지 OCR 서비스입니다. FastAPI와 PaddleOCR PP-OCRv5 한국어 모델을 사용하며 외부에 공개하지 않습니다. Spring Boot 백엔드가 MinIO에서 이미지를 읽어 Docker 내부 HTTP로 전달하고, 이 서비스는 텍스트와 평균 신뢰도만 반환합니다.

## 책임 경계

- Spring Boot: 공개 REST API, 책·페이지 데이터, MinIO, OCR 상태, 작업 선점, 재시도와 revision 정합성
- Python OCR: 이미지 검증·전처리, PaddleOCR 추론, 읽기 순서 정렬과 신뢰도 계산
- Python 서비스는 PostgreSQL, MinIO, 책 ID와 페이지 ID를 알지 못합니다.
- `X-Ocr-Request-Id`는 두 서비스 로그를 연결하는 용도로만 사용합니다.

## 내부 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health/live` | 프로세스 liveness |
| `GET` | `/health/ready` | 모델 로딩 완료 여부 |
| `POST` | `/internal/v1/ocr` | multipart 이미지 OCR |

OCR 요청은 JPEG, PNG, WebP만 허용하고 기본 10MB, 40메가픽셀로 제한합니다. EXIF 방향을 적용한 뒤 문서 방향·텍스트 줄 방향 분류와 왜곡 보정을 수행합니다. 영역은 세로·가로 위치순으로 정렬하고 같은 줄은 공백, 다른 줄은 개행으로 합칩니다.

```http
POST /internal/v1/ocr
Content-Type: multipart/form-data
X-Ocr-Request-Id: 1d0d8f06-6a4e-4d79-877c-e1af5bcc6555

image=<binary>
```

```json
{
  "text": "페이지에서 인식한 전체 텍스트",
  "confidence": 0.9421,
  "engine": "paddleocr",
  "model": "PP-OCRv5-korean",
  "processingTimeMs": 1830
}
```

글자가 없으면 빈 텍스트와 `confidence: null`을 반환합니다. 오류 코드는 `INVALID_IMAGE`, `IMAGE_TOO_LARGE`, `UNSUPPORTED_IMAGE_TYPE`, `OCR_BUSY`, `OCR_NOT_READY`, `OCR_INFERENCE_FAILED`입니다.

## Docker 실행

저장소 루트에서 전체 환경을 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

OCR 서비스에는 host `ports`가 없고 Docker 네트워크의 `ocr-service:8000`으로만 접근할 수 있습니다. 첫 실행에는 모델 다운로드와 초기화 시간이 필요합니다. 모델 cache는 `ocr-model-cache` volume에 유지됩니다. CPU 추론은 페이지 크기와 호스트 성능에 따라 시간이 달라지며, 모델 로딩과 추론을 위해 충분한 메모리와 디스크 여유 공간을 확보해야 합니다. worker는 모델 메모리 중복을 막기 위해 1개로 고정됩니다.

## 로컬 개발과 검증

Python 3.11과 [uv](https://docs.astral.sh/uv/)가 필요합니다.

```powershell
Set-Location ocr-service
uv sync --frozen
uv run ruff check .
uv run mypy src
uv run pytest
```

기본 테스트는 모델을 대체한 빠른 계약 테스트를 실행합니다. 실제 PP-OCRv5 모델로 한국어·영어 혼합 및 90도 회전 fixture를 확인하려면 다음처럼 실행합니다.

```powershell
$env:RUN_OCR_MODEL_TESTS = '1'
$env:OCR_TEST_FONT_PATH = 'C:\Windows\Fonts\malgun.ttf'
uv run pytest tests/test_model_smoke.py -v
```

CI와 Docker 이미지에는 Noto CJK 폰트가 설치됩니다. 품질 스모크 테스트는 공백을 정규화한 뒤 fixture의 핵심 한국어 문장이 결과에 포함되는지 검증합니다.

## 장애 확인

- `/health/live`만 성공하고 `/health/ready`가 503이면 모델 다운로드·초기화 로그를 확인합니다.
- `OCR_BUSY`는 동시 추론 한도를 초과한 상태이며 Spring이 자동 재시도합니다.
- 손상 이미지와 지원하지 않는 MIME 타입은 재시도하지 않고 페이지를 `failed`로 전환합니다.
- 추론 오류, readiness 오류와 연결 실패는 Spring이 최대 횟수까지 재시도합니다.
- 서비스 재시작 후 모델을 다시 내려받는다면 `ocr-model-cache` volume 연결과 `PADDLE_PDX_CACHE_HOME` 설정을 확인합니다.
