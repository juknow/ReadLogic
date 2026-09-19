package com.readlogic.backend.ocr.application;

import com.readlogic.backend.storage.PageImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class OcrJobProcessor {

	private static final Logger log = LoggerFactory.getLogger(OcrJobProcessor.class);

	private final PageImageStorage imageStorage;
	private final OcrClient ocrClient;
	private final OcrJobCoordinator coordinator;

	public OcrJobProcessor(
			PageImageStorage imageStorage,
			OcrClient ocrClient,
			OcrJobCoordinator coordinator
	) {
		this.imageStorage = imageStorage;
		this.ocrClient = ocrClient;
		this.coordinator = coordinator;
	}

	public void process(OcrJob job) {
		UUID requestId = UUID.randomUUID();
		try {
			PageImageStorage.StoredImage storedImage = imageStorage.load(job.objectKey());
			OcrImage image = new OcrImage(storedImage.content(), storedImage.contentType(), job.fileName());
			OcrResult result = ocrClient.recognize(image, requestId, job.language());
			if (!coordinator.complete(job, result, Instant.now())) {
				log.info("Discarded stale OCR result. pageId={} requestId={}", job.pageId(), requestId);
			}
		} catch (OcrClientException exception) {
			coordinator.recordFailure(
					job,
					exception.getCode(),
					exception.getMessage(),
					exception.isRetryable(),
					Instant.now()
			);
			log.warn("OCR request failed. pageId={} requestId={} code={} retryable={}",
					job.pageId(), requestId, exception.getCode(), exception.isRetryable());
		} catch (RuntimeException exception) {
			coordinator.recordFailure(
					job,
					"OCR_IMAGE_READ_FAILED",
					"OCR 대상 이미지를 읽거나 처리하지 못했습니다.",
					true,
					Instant.now()
			);
			log.warn("OCR job failed before inference. pageId={} requestId={}", job.pageId(), requestId, exception);
		}
	}
}
