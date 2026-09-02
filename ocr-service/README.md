# ReadLogic OCR Service

ReadLogic의 무상태 내부 페이지 OCR 서비스입니다. FastAPI와 PaddleOCR PP-OCRv5를 사용해 한국어 품질을 유지하면서 영어, 일본어, 중국어, 문서 보정과 paragraph 구조를 반환합니다. 외부에 공개하지 않습니다.

현재 Python module 구조, 실제 model, routing/paragraph 알고리즘과 운영 정책은 [ARCHITECTURE.md](ARCHITECTURE.md)를 참고합니다.

## 책임 경계

- Spring Boot: 공개 REST API, 책·페이지 데이터, MinIO, OCR 상태, 작업 선점, 재시도와 revision 정합성
- Python OCR: 이미지 검증·보정, 공통 detection, 다국어 recognition, 읽기 순서와 paragraph 구조화
- Python 서비스는 PostgreSQL, MinIO, 책 ID와 페이지 ID를 알지 못합니다.
- `X-Ocr-Request-Id`는 두 서비스 로그를 연결하는 용도로만 사용합니다.

## 내부 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health/live` | 프로세스 liveness |
| `GET` | `/health/ready` | 모델 로딩 완료 여부 |
| `POST` | `/internal/v1/ocr` | multipart 이미지 OCR |

OCR 요청은 JPEG, PNG, WebP만 허용하고 기본 10MB, 40메가픽셀로 제한합니다. EXIF, 문서 방향, UVDoc 왜곡 보정 후 공통 detection을 실행하고 요청 언어에 맞는 recognizer를 선택합니다. 좌표 기반으로 line, column과 paragraph를 재구성합니다.

```http
POST /internal/v1/ocr
Content-Type: multipart/form-data
X-Ocr-Request-Id: 1d0d8f06-6a4e-4d79-877c-e1af5bcc6555

image=<binary>
language=ko|en|ja|zh|auto
```

```json
{
  "text": "페이지에서 인식한 전체 텍스트",
  "confidence": 0.9421,
  "engine": "paddleocr",
  "model": "korean_PP-OCRv5_mobile_rec",
  "processingTimeMs": 1830,
  "document": {
    "schemaVersion": 1,
    "requestedLanguage": "ko",
    "detectedLanguage": "ko",
    "coordinateSpace": "corrected_image",
    "image": {"width": 1600, "height": 2400},
    "correction": {
      "exifApplied": true,
      "orientationApplied": false,
      "rotationDegrees": 0,
      "unwarpingApplied": true,
      "fallbackUsed": false
    },
    "models": {
      "orientation": "PP-LCNet_x1_0_doc_ori",
      "unwarping": "UVDoc",
      "detector": "PP-OCRv5_server_det",
      "textLineOrientation": "PP-LCNet_x1_0_textline_ori",
      "recognizers": ["korean_PP-OCRv5_mobile_rec"]
    },
    "warnings": [],
    "paragraphs": [
      {
        "id": 1,
        "order": 1,
        "type": "body",
        "text": "문단 텍스트",
        "bbox": [100, 200, 1450, 600],
        "confidence": 0.97,
        "lines": []
      }
    ]
  }
}
```

`language`를 생략하면 기존과 같이 `ko`입니다. 글자가 없으면 200과 빈 text/paragraph, `confidence: null`, `NO_TEXT_DETECTED` warning을 반환합니다. 오류 코드는 `INVALID_OCR_LANGUAGE`, `INVALID_IMAGE`, `IMAGE_TOO_LARGE`, `UNSUPPORTED_IMAGE_TYPE`, `OCR_BUSY`, `OCR_NOT_READY`, `OCR_TIMEOUT`, `OCR_INFERENCE_FAILED`입니다.

## Docker 실행

저장소 루트에서 전체 환경을 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

OCR 서비스에는 host `ports`가 없고 Docker 네트워크의 `ocr-service:8000`으로만 접근할 수 있습니다. 첫 실행에는 모델 다운로드와 초기화 시간이 필요합니다. 모델 cache는 `ocr-model-cache` volume에 유지됩니다. 기본 preload는 `ko,en,ja,zh`이며 `ja/zh`는 같은 CJK model을 공유합니다. 제한된 환경에서는 `.env`의 `OCR_PRELOAD_LANGUAGES=ko`로 한국어만 먼저 올리고 다른 model을 lazy load할 수 있습니다. worker와 기본 inference concurrency는 1입니다.

## 로컬 개발과 검증

Python 3.11과 [uv](https://docs.astral.sh/uv/)가 필요합니다.

```powershell
Set-Location ocr-service
uv sync --frozen
uv run ruff check .
uv run mypy src
uv run pytest
```

기본 테스트는 모델을 대체한 API, geometry, routing, paragraph와 metric 테스트를 실행합니다. 실제 PP-OCRv5 Korean baseline과 90도 회전을 확인하려면 다음처럼 실행합니다.

```powershell
$env:RUN_OCR_MODEL_TESTS = '1'
$env:OCR_TEST_FONT_PATH = 'C:\Windows\Fonts\malgun.ttf'
uv run pytest tests/test_model_smoke.py -v
```

CI와 Docker 이미지에는 Noto CJK 폰트가 설치됩니다. model test는 `tests/golden/korean_baseline.json`의 reference와 character accuracy 기준을 사용합니다. Docker image size, cold/warm startup과 RSS는 Docker가 가능한 배포 후보 환경에서 별도로 측정해야 합니다.

## 장애 확인

- `/health/live`만 성공하고 `/health/ready`가 503이면 모델 다운로드·초기화 로그를 확인합니다.
- `OCR_BUSY`는 동시 추론 한도를 초과한 상태이며 Spring이 자동 재시도합니다.
- `OCR_TIMEOUT`은 기본 105초 stage deadline을 넘은 상태이며 Spring이 자동 재시도합니다.
- 손상 이미지와 지원하지 않는 MIME 타입은 재시도하지 않고 페이지를 `failed`로 전환합니다.
- 추론 오류, readiness 오류와 연결 실패는 Spring이 최대 횟수까지 재시도합니다.
- 서비스 재시작 후 모델을 다시 내려받는다면 `ocr-model-cache` volume 연결과 `PADDLE_PDX_CACHE_HOME` 설정을 확인합니다.
