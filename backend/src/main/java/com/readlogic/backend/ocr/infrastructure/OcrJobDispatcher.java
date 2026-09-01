package com.readlogic.backend.ocr.infrastructure;

import com.readlogic.backend.ocr.application.OcrJob;
import com.readlogic.backend.ocr.application.OcrJobCoordinator;
import com.readlogic.backend.ocr.application.OcrJobProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(prefix = "app.ocr", name = "enabled", havingValue = "true")
public class OcrJobDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OcrJobDispatcher.class);

	private final OcrJobCoordinator coordinator;
	private final OcrJobProcessor processor;
	private final ThreadPoolTaskExecutor executor;
	private final int concurrency;
	private final AtomicInteger inFlight = new AtomicInteger();

	public OcrJobDispatcher(
			OcrJobCoordinator coordinator,
			OcrJobProcessor processor,
			@Qualifier("ocrTaskExecutor") ThreadPoolTaskExecutor executor,
			OcrProperties properties
	) {
		this.coordinator = coordinator;
		this.processor = processor;
		this.executor = executor;
		this.concurrency = properties.concurrency();
	}

	@Scheduled(fixedDelayString = "${app.ocr.poll-interval:2s}")
	public void poll() {
		Instant now = Instant.now();
		int recovered = coordinator.recoverStale(now);
		if (recovered > 0) {
			log.warn("Recovered stale OCR jobs. count={}", recovered);
		}

		int available = concurrency - inFlight.get();
		if (available <= 0) {
			return;
		}
		List<OcrJob> jobs = coordinator.claim(available, now);
		jobs.forEach(this::dispatch);
	}

	private void dispatch(OcrJob job) {
		inFlight.incrementAndGet();
		try {
			executor.execute(() -> {
				try {
					processor.process(job);
				} finally {
					inFlight.decrementAndGet();
				}
			});
		} catch (RejectedExecutionException exception) {
			inFlight.decrementAndGet();
			coordinator.recordFailure(
					job,
					"OCR_DISPATCH_FAILED",
					"OCR 작업 실행기를 사용할 수 없습니다.",
					true,
					Instant.now()
			);
		}
	}
}
