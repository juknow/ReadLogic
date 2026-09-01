package com.readlogic.backend.ocr.application;

import java.util.UUID;

public interface OcrClient {

	OcrResult recognize(OcrImage image, UUID requestId);
}
