package com.readlogic.backend.ocr.application;

import java.util.UUID;

public record OcrJob(
		UUID pageId,
		int revision,
		int attemptCount,
		String objectKey,
		String fileName,
		String contentType
) {
}
