package com.readlogic.backend.ocr.application;

import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookPageRepository;
import com.readlogic.backend.book.domain.OcrStatus;
import com.readlogic.backend.book.domain.TextSource;
import com.readlogic.backend.ocr.infrastructure.OcrProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OcrJobCoordinator {

	private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(5);
	private static final Duration LATER_RETRY_DELAY = Duration.ofSeconds(30);
	private static final int MAX_ERROR_MESSAGE_LENGTH = 2_000;

	private final BookPageRepository pageRepository;
	private final OcrProperties properties;

	public OcrJobCoordinator(BookPageRepository pageRepository, OcrProperties properties) {
		this.pageRepository = pageRepository;
		this.properties = properties;
	}

	@Transactional
	public List<OcrJob> claim(int requestedCount, Instant now) {
		int batchSize = Math.min(requestedCount, properties.batchSize());
		return pageRepository.lockPendingOcrPages(now, batchSize).stream()
				.filter(page -> page.claimOcr(now))
				.map(this::toJob)
				.toList();
	}

	@Transactional
	public boolean complete(OcrJob job, OcrResult result, Instant completedAt) {
		return pageRepository.completeOcr(
				job.pageId(),
				job.revision(),
				result.text(),
				result.confidence(),
				result.engine(),
				result.model(),
				completedAt,
				OcrStatus.READY,
				OcrStatus.PROCESSING,
				TextSource.OCR
		) == 1;
	}

	@Transactional
	public void recordFailure(OcrJob job, String code, String message, boolean retryable, Instant failedAt) {
		String safeMessage = truncate(message);
		if (!retryable || job.attemptCount() >= properties.maxAttempts()) {
			pageRepository.failOcr(
					job.pageId(), job.revision(), code, safeMessage, failedAt,
					OcrStatus.FAILED, OcrStatus.PROCESSING
			);
			return;
		}
		Duration delay = job.attemptCount() == 1 ? FIRST_RETRY_DELAY : LATER_RETRY_DELAY;
		pageRepository.scheduleOcrRetry(
				job.pageId(), job.revision(), code, safeMessage, failedAt, failedAt.plus(delay),
				OcrStatus.PENDING, OcrStatus.PROCESSING
		);
	}

	@Transactional
	public int recoverStale(Instant now) {
		Instant startedBefore = now.minus(properties.staleAfter());
		return (int) pageRepository.lockStaleOcrPages(startedBefore).stream()
				.filter(page -> page.recoverStaleOcr(startedBefore, now))
				.count();
	}

	private OcrJob toJob(BookPage page) {
		return new OcrJob(
				page.getId(),
				page.getOcrRevision(),
				page.getOcrAttemptCount(),
				page.getObjectKey(),
				page.getOriginalFileName(),
				page.getMimeType()
		);
	}

	private String truncate(String message) {
		String value = message == null || message.isBlank() ? "OCR 처리 중 오류가 발생했습니다." : message;
		return value.length() <= MAX_ERROR_MESSAGE_LENGTH
				? value
				: value.substring(0, MAX_ERROR_MESSAGE_LENGTH);
	}
}
