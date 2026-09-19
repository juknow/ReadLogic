# OCR Service Architecture

이 문서는 `ocr-service`의 현재 FastAPI/PaddleOCR 구현과 OCR 품질·운영 규칙을 설명한다. 실행 예시는 [README.md](README.md), 제품 범위는 루트의 [READLOGIC_PROJECT_CONTEXT.md](../READLOGIC_PROJECT_CONTEXT.md)를 따른다.

## 1. 책임 경계

OCR 서비스는 이미지 한 장을 받아 보정, detection, recognition, 읽기 순서와 문단 구조를 반환하는 내부 무상태 서비스다.

```text
Spring Boot backend
  ↓ image + language + request ID
Python OCR service
  ↓ text + confidence + versioned document
Spring Boot persistence
```

- PostgreSQL, MinIO, book/page ID와 OCR 재시도 상태를 알지 못한다.
- 요청 결과를 로컬 DB에 저장하지 않는다.
- `X-Ocr-Request-Id`는 서비스 간 log correlation 용도다.
- 외부 공개 endpoint가 아니며 compose에서 host port를 열지 않는다.

## 2. 디렉터리 구조

`.venv`, model cache, pytest cache와 build artifact는 제외했다.

```text
ocr-service/
├─ src/readlogic_ocr/
│  ├─ __init__.py
│  ├─ main.py
│  ├─ config.py
│  ├─ errors.py
│  ├─ image.py
│  ├─ models.py
│  ├─ preprocessing.py
│  ├─ detection.py
│  ├─ recognition.py
│  ├─ ordering.py
│  ├─ paragraphs.py
│  ├─ engine.py
│  ├─ runtime.py
│  └─ schemas.py
├─ tests/
│  ├─ golden/korean_baseline.json
│  ├─ quality_metrics.py
│  ├─ test_api.py
│  ├─ test_config.py
│  ├─ test_detection.py
│  ├─ test_engine.py
│  ├─ test_model_smoke.py
│  ├─ test_models.py
│  ├─ test_multilingual_engine.py
│  ├─ test_ordering.py
│  ├─ test_paragraphs.py
│  ├─ test_preprocessing.py
│  ├─ test_quality_metrics.py
│  └─ test_recognition.py
├─ Dockerfile
├─ pyproject.toml
└─ uv.lock
```

## 3. Python module 역할

| module | 역할 |
| --- | --- |
| `main.py` | FastAPI lifecycle, health/OCR route, upload contract와 response 조립 |
| `config.py` | `OCR_SERVICE_` 환경변수, size/concurrency/preload/deadline 검증 |
| `errors.py` | HTTP로 변환되는 안정적인 service error code |
| `image.py` | Pillow decode, pixel 검증, EXIF transpose, RGB→OpenCV BGR |
| `models.py` | Paddle와 API schema에 독립적인 region/line/paragraph/correction model |
| `preprocessing.py` | document orientation와 UVDoc adapter, 실패 warning/fallback |
| `detection.py` | 공통 PP-OCRv5 detector, polygon 정규화와 perspective crop |
| `recognition.py` | line orientation, recognizer registry, 명시/auto language routing |
| `ordering.py` | region→line, column 분리와 deterministic reading order |
| `paragraphs.py` | 좌표 기반 paragraph grouping과 언어별 line merge |
| `engine.py` | 전체 stage orchestration, timeout 확인과 compatibility parsing |
| `runtime.py` | engine lifecycle, readiness, initialization lock와 inference semaphore |
| `schemas.py` | health, error와 versioned structured OCR Pydantic response |

FastAPI route에는 geometry나 routing 알고리즘을 넣지 않는다. Paddle-specific result parsing은 adapter에 두고 ordering/paragraph 로직은 실제 model 없이 테스트할 수 있게 유지한다.

## 4. FastAPI lifecycle과 endpoint

```text
application startup
  ↓ RuntimeState.initialize (thread pool)
PaddleOcrEngine 생성 + configured preload
  ├─ success → /health/ready 200
  └─ failure → log + /health/ready 503
```

| Method | Path | 역할 |
| --- | --- | --- |
| `GET` | `/health/live` | process liveness |
| `GET` | `/health/ready` | configured core/preload model 준비 여부 |
| `POST` | `/internal/v1/ocr` | 한 장의 multipart OCR |

OCR multipart:

- `image`: JPEG, PNG 또는 WebP binary
- `language`: optional `ko/en/ja/zh/auto`, 생략·빈 값은 `ko`
- header `X-Ocr-Request-Id`: UUID 필수

기존 top-level `text`, `confidence`, `engine`, `model`, `processingTimeMs`를 유지하고 `document`를 additive하게 반환한다.

## 5. Validation과 decode

처리 순서:

1. content type allow-list 확인
2. stream을 최대 byte + 1만 읽어 byte 제한 확인
3. Pillow decode
4. decoded width × height 제한 확인
5. EXIF orientation 적용
6. RGB normalization
7. NumPy/OpenCV BGR 변환

기본 제한은 10MB와 40MP다. 잘못된 MIME, 손상 이미지, 크기 초과는 model을 실행하지 않고 4xx로 종료한다.

## 6. OCR pipeline

```text
Validated BGR image
  ↓ PP-LCNet_x1_0_doc_ori
Document orientation (0/90/180/270)
  ↓ UVDoc
Document unwarping / perspective / curvature correction
  ↓ PP-OCRv5_server_det (한 번)
Quadrilateral text regions + detection confidence
  ↓ OpenCV perspective transform
Normalized region crops
  ↓ PP-LCNet_x1_0_textline_ori
Text-line orientation
  ↓ language router
KO / EN / shared CJK recognition
  ↓
RecognizedRegion → OcrLine → OcrParagraph
```

언어마다 high-level PaddleOCR pipeline을 다시 실행하지 않는다. correction과 detection 결과를 공유하고 recognition만 routing한다.

## 7. 실제 Paddle model

| 단계 | model |
| --- | --- |
| document orientation | `PP-LCNet_x1_0_doc_ori` |
| document unwarping | `UVDoc` |
| text detection | `PP-OCRv5_server_det` |
| text-line orientation | `PP-LCNet_x1_0_textline_ori` |
| Korean recognition | `korean_PP-OCRv5_mobile_rec` |
| English recognition | `en_PP-OCRv5_mobile_rec` |
| Japanese/Chinese recognition | `PP-OCRv5_server_rec` |

`ja`와 `zh`는 같은 CJK recognizer instance를 공유한다. PaddleOCR와 PaddlePaddle version은 `pyproject.toml`에 고정한다.

## 8. Model registry와 startup

registry key:

```text
recognizer:ko
recognizer:en
recognizer:cjk  ← ja/zh shared
```

- key별 `Lock`으로 같은 model을 동시에 중복 초기화하지 않는다.
- 기본 `OCR_SERVICE_PRELOAD_LANGUAGES=ko,en,ja,zh`는 readiness 전에 지원 recognizer를 준비한다.
- 제한된 환경에서는 `ko`만 설정해 English/CJK를 첫 요청에 lazy load할 수 있다.
- 빈 preload 값은 모든 recognizer lazy load다.
- `auto`는 preload 값으로 허용하지 않는다. 실제 model key가 아니기 때문이다.
- model weight는 Docker image에 bake하지 않고 `/models` named volume에 cache한다.

공통 preprocessor, detector와 line orienter는 engine 생성 시 초기화된다. preload 중 오류가 나면 engine readiness는 503이다. lazy model은 key별 lock 안에서 한 번만 생성된다.

## 9. 언어 routing

### 명시 언어

요청 값이 `ko/en/ja/zh`이면 해당 recognizer를 primary로 실행한다. 빈 결과 또는 confidence `< 0.70`인 line에만 대체 recognizer를 한 번 적용한다.

대체 결과는 다음 경우에만 채택한다.

- primary text가 비고 alternative가 존재
- 또는 alternative score가 primary보다 `0.05` 이상 높음

Hangul을 포함한 primary 결과가 confidence `0.70` 이상이면 다른 model이 교체하지 못한다.

### 자동 언어

1. region이 5개 이하면 전체, 많으면 페이지에 분산된 최대 5개 index 선택
2. Korean, English, CJK 후보를 sample crop에 실행
3. confidence, script ratio와 invalid character penalty로 점수 계산
4. 1·2위 차이가 `0.10` 이상이면 1위 model로 전체 line 실행
5. 차이가 작으면 상위 두 model만 전체 line에서 비교
6. text/confidence/script가 의미 있게 나아질 때만 line 교체

모든 line에 세 model을 무조건 실행하지 않는다.

### detected language

- Hangul 존재: `ko`
- Kana 존재: `ja`
- Latin 존재: `en`
- 명시 `ja/zh`의 Han: 요청 언어 유지
- auto의 Han-only: `und` + `CJK_LANGUAGE_UNCERTAIN`
- 둘 이상의 script가 최종 line에 존재: `mixed`
- 숫자/기호 중심: `und`

## 10. Korean preservation

한국어는 범용 CJK model로 대체하지 않는다.

- `language` 생략 시 항상 `ko`
- primary model은 `korean_PP-OCRv5_mobile_rec`
- Hangul 고신뢰 line은 fallback 교체 금지
- top-level confidence는 기존 region 단순 평균을 유지
- `tests/golden/korean_baseline.json`이 reference text, 허용 accuracy와 model 정보를 기록
- 실제 model smoke는 기본 test와 분리해 opt-in 실행

공통 detection/crop 변화가 같은 Korean model의 결과에도 영향을 줄 수 있으므로 model upgrade와 geometry 변경은 baseline을 함께 검토한다.

## 11. 좌표와 내부 model

```text
RecognizedRegion
  polygon, bbox, text,
  detection/recognition confidence,
  language, model

OcrLine
  id, order, bbox, polygon,
  fragments, text, confidence,
  language, model, baseline

OcrParagraph
  id, order, type,
  bbox, text, confidence, lines
```

모든 반환 좌표는 `corrected_image` 기준이다. UVDoc은 비선형 변환일 수 있어 원본 좌표라고 가장하지 않는다. response의 image width/height가 해당 좌표 공간을 정의한다. 원본 좌표 역변환과 corrected image 저장은 현재 범위 밖이다.

## 12. Reading order

### region에서 line

- center y 차이 tolerance: `max(8px, median height × 0.6)`
- y overlap, 비슷한 높이와 geometry를 사용
- 같은 line fragment는 x 오름차순
- line bbox/polygon은 fragment union

### column

- line bbox의 left/right 경계 사이에서 비어 있는 수직 gap 후보를 찾는다.
- gap이 median line height의 1.5배 이상이고 양쪽에 각각 2줄 이상 있을 때 분리한다.
- 가장 넓은 유효 gap을 기준으로 좌우 영역을 나누고 같은 규칙을 재귀 적용한다.
- 여러 column을 가로지르는 상단 line은 spanning title 후보로 먼저 배치한다.

### fallback

column을 찾지 못하면 top-to-bottom, 같은 band에서 left-to-right 순서를 사용한다. 수평 책 페이지가 acceptance 대상이며 vertical CJK reading order는 best effort다.

## 13. Paragraph grouping과 merge

문단 경계는 문자열 newline 하나가 아니라 좌표 신호를 결합한다.

- 같은 column 여부
- 일반 line gap 대비 vertical gap
- 다음 line start x와 indent
- 이전 line width와 column 끝 도달 여부
- median box height와 title 크기
- whitespace와 spanning title

type은 `title`, `body`, `unknown`만 사용한다. 확신할 수 없으면 `unknown`이며 OCR 실패가 아니다.

언어별 merge:

- Korean: line 사이 기본 공백, 문장부호 앞 불필요 공백 제거
- English: 기본 공백, 안전한 line-end hyphen만 결합
- Japanese/Chinese: line 사이 기본 무공백, punctuation 공백 정리
- paragraph 사이는 `\n\n`

line 원문과 좌표는 structured response에 유지한다. paragraph/line confidence는 인식 문자 수로 가중한다.

## 14. Correction metadata

response에는 다음을 기록한다.

- EXIF 적용 여부
- document orientation 적용 여부와 회전 각도
- UVDoc 적용 여부
- correction fallback 여부

Paddle preprocessor가 실패하면 EXIF 적용 image를 사용하고 warning을 남긴다. correction 실패만으로 전체 요청을 실패시키지 않는다. 별도 Hough/page contour dependency는 추가하지 않았다.

## 15. 실패와 fallback matrix

| 상황 | 처리 |
| --- | --- |
| 지원하지 않는 MIME | 415, 재시도 불필요 |
| decode/size 실패 | 400, 재시도 불필요 |
| engine 미준비 | 503 `OCR_NOT_READY` |
| inference slot 사용 중 | 429 `OCR_BUSY` |
| correction 실패 | 원본 기반 계속 + warning |
| 낮은 recognition confidence | 제한된 alternative model 1회 |
| 언어 판정 불가 | `und`, text는 보존 |
| text 없음 | 200, 빈 paragraphs + `NO_TEXT_DETECTED` |
| paragraph grouping 예외 | line당 paragraph fallback + warning |
| stage deadline 초과 | 504 `OCR_TIMEOUT` |
| 그 밖의 Paddle 예외 | 500 `OCR_INFERENCE_FAILED` |

backend는 429/5xx를 retryable로 분류한다. 한 요청 안에서 correction/model fallback을 무한 반복하지 않는다.

## 16. Concurrency와 timeout

- Uvicorn worker: 1
- 기본 inference concurrency: 1
- `RuntimeState` 전체 inference를 `BoundedSemaphore`로 보호
- slot을 즉시 얻지 못하면 queueing 대신 429
- engine initialization은 별도 lock으로 single-flight
- default stage deadline: 105초
- backend read timeout: 120초

deadline은 Python stage 사이에서 확인한다. 실행 중인 native Paddle call을 강제 중단하지는 못하며 native call 반환 직후 timeout으로 처리한다.

## 17. Docker와 model cache

- base: `python:3.11.16-slim-bookworm`
- CPU `paddlepaddle==3.2.0`
- `libgomp1`, `libgl1`, `libglib2.0-0`, Noto CJK 유지
- `PADDLE_HOME=/models/paddle`
- `PADDLE_PDX_CACHE_HOME=/models/paddlex`
- compose named volume: `ocr-model-cache:/models`
- model weight는 image layer에 포함하지 않음

현재 개발 호스트에 Docker CLI가 없어서 실제 image size, empty/warm cache startup, RSS와 first-language latency는 실측하지 못했다. 이 값은 Docker 가능한 배포 후보 환경에서 확인해야 한다.

## 18. 테스트와 품질 gate

| 영역 | 파일 |
| --- | --- |
| API/lifecycle/concurrency | `test_api.py` |
| 설정/preload | `test_config.py` |
| polygon/crop | `test_detection.py`, `test_models.py` |
| Paddle result compatibility | `test_engine.py` |
| correction | `test_preprocessing.py` |
| language routing/fallback | `test_recognition.py`, `test_multilingual_engine.py` |
| line/column order | `test_ordering.py` |
| paragraph/merge | `test_paragraphs.py` |
| CER/line metric | `quality_metrics.py`, `test_quality_metrics.py` |
| real Korean model | `test_model_smoke.py` + golden manifest |

현재 자동화된 기반:

- 합성 Korean reference의 CER/character accuracy와 opt-in real-model smoke
- exact line accuracy와 line precision/recall/F1
- reading-order pair accuracy
- paragraph boundary precision/recall/F1

실제 라이선스 fixture suite에 적용할 acceptance 목표:

- Korean aggregate character accuracy 하락 최대 0.5%p 목표
- 개별 Korean fixture 2%p 이상 하락은 검토 필요
- reading-order pair accuracy 목표 0.95 이상
- paragraph boundary F1 목표 0.90 이상
- forced Korean warmed p95 목표 기존 1.2배 이내
- auto warmed p95 목표 기존 2배 이내
- warmed RSS 목표 기존 Korean-only 2배 이내

이 수치는 목표이며 현재 저장소의 합성 fixture만으로 모두 달성했다고 간주하지 않는다. 특히 마지막 세 성능 값은 Docker 실측 전까지 미확인이다. fixture는 자체 제작 또는 공개 라이선스 자료만 저장하고 출처를 manifest에 기록해야 한다.

## 19. PP-StructureV3 결정

현재는 PP-StructureV3와 `paddleocr[doc-parser]`를 사용하지 않는다.

이유:

- 주요 입력이 일반 책 본문 페이지다.
- 기존 Korean recognition path를 유지해야 한다.
- table/image/formula 구조가 현재 제품 contract에 필요하지 않다.
- layout model은 CPU, RAM, cache, startup과 유지보수 비용을 늘린다.
- 좌표 기반 column/paragraph heuristic을 독립 unit test할 수 있다.

표 cell, 잡지형 복합 layout, image 영역 또는 vertical CJK 정확도가 제품 요구가 되면 별도 검증 PR에서 PP-StructureV3를 비교한다.

## 20. 확장 규칙

- Paddle API 변화는 adapter module 안에서 흡수한다.
- 새 recognizer는 registry key/model mapping과 response model 기록을 함께 갱신한다.
- geometry 규칙은 `ordering.py`/`paragraphs.py`에 두고 FastAPI route에 넣지 않는다.
- 새 correction dependency는 기존 Paddle/OpenCV로 fixture gate를 충족하지 못한 증거가 있을 때만 추가한다.
- schema 변경은 `schemaVersion`과 backend/frontend compatibility를 함께 설계한다.
- model, endpoint, env, fallback 또는 테스트 gate가 바뀌면 이 문서를 같은 PR에서 갱신한다.
