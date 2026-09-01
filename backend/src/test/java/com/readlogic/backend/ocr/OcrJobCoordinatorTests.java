package com.readlogic.backend.ocr;

import com.readlogic.backend.book.application.BookApplicationService;
import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.book.domain.OcrStatus;
import com.readlogic.backend.book.domain.TextSource;
import com.readlogic.backend.ocr.application.OcrJob;
import com.readlogic.backend.ocr.application.OcrJobCoordinator;
import com.readlogic.backend.ocr.application.OcrResult;
import com.readlogic.backend.storage.PageImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

@SpringBootTest
class OcrJobCoordinatorTests {

	@Autowired
	private OcrJobCoordinator coordinator;

	@Autowired
	private BookRepository bookRepository;

	@Autowired
	private BookApplicationService bookService;

	@MockitoBean
	private PageImageStorage imageStorage;

	@BeforeEach
	void setUp() {
		bookRepository.deleteAll();
		reset(imageStorage);
	}

	@Test
	void claimsEachPendingJobOnlyOnceAndStoresSuccessfulResult() {
		BookPage page = savePendingPage();
		Instant now = Instant.now().plusSeconds(1);

		OcrJob job = coordinator.claim(1, now).getFirst();
		assertThat(coordinator.claim(1, now)).isEmpty();
		assertThat(job.attemptCount()).isEqualTo(1);
		assertThat(reload(page).getOcrStatus()).isEqualTo(OcrStatus.PROCESSING);

		boolean stored = coordinator.complete(
				job,
				new OcrResult("인식 결과", new BigDecimal("0.9123"), "paddleocr", "PP-OCRv5-korean"),
				now.plusSeconds(1)
		);

		BookPage completed = reload(page);
		assertThat(stored).isTrue();
		assertThat(completed.getOcrStatus()).isEqualTo(OcrStatus.READY);
		assertThat(completed.getExtractedText()).isEqualTo("인식 결과");
		assertThat(completed.getOcrConfidence()).isEqualByComparingTo("0.9123");
		assertThat(completed.getTextSource()).isEqualTo(TextSource.OCR);
	}

	@Test
	void retriesTemporaryErrorsAndFailsAfterThirdAttempt() {
		BookPage page = savePendingPage();
		Instant startedAt = Instant.now().plusSeconds(1);

		OcrJob first = coordinator.claim(1, startedAt).getFirst();
		coordinator.recordFailure(first, "OCR_NOT_READY", "준비 중", true, startedAt);
		BookPage afterFirst = reload(page);
		assertThat(afterFirst.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
		assertThat(afterFirst.getOcrNextAttemptAt()).isEqualTo(startedAt.plusSeconds(5));

		OcrJob second = coordinator.claim(1, startedAt.plusSeconds(6)).getFirst();
		coordinator.recordFailure(second, "OCR_BUSY", "사용 중", true, startedAt.plusSeconds(6));
		assertThat(reload(page).getOcrNextAttemptAt()).isEqualTo(startedAt.plusSeconds(36));

		OcrJob third = coordinator.claim(1, startedAt.plusSeconds(37)).getFirst();
		coordinator.recordFailure(third, "OCR_INFERENCE_FAILED", "추론 실패", true, startedAt.plusSeconds(37));
		BookPage failed = reload(page);
		assertThat(failed.getOcrStatus()).isEqualTo(OcrStatus.FAILED);
		assertThat(failed.getOcrAttemptCount()).isEqualTo(3);
		assertThat(failed.getOcrLastErrorCode()).isEqualTo("OCR_INFERENCE_FAILED");
	}

	@Test
	void failsPermanentErrorsWithoutRetry() {
		BookPage page = savePendingPage();
		Instant now = Instant.now().plusSeconds(1);
		OcrJob job = coordinator.claim(1, now).getFirst();

		coordinator.recordFailure(job, "INVALID_IMAGE", "손상된 이미지", false, now.plusSeconds(1));

		assertThat(reload(page).getOcrStatus()).isEqualTo(OcrStatus.FAILED);
	}

	@Test
	void recoversStaleProcessingJobs() {
		BookPage page = savePendingPage();
		Instant startedAt = Instant.now().plusSeconds(1);
		coordinator.claim(1, startedAt);

		int recovered = coordinator.recoverStale(startedAt.plusSeconds(301));

		BookPage restored = reload(page);
		assertThat(recovered).isEqualTo(1);
		assertThat(restored.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
		assertThat(restored.getOcrLastErrorCode()).isEqualTo("OCR_STALE_JOB_RECOVERED");
	}

	@Test
	void discardsResultAfterManualTextChangesRevision() {
		BookPage page = savePendingPage();
		Instant now = Instant.now().plusSeconds(1);
		OcrJob job = coordinator.claim(1, now).getFirst();

		bookService.updatePage(page.getBookId(), page.getId(), null, "사용자 교정 본문");
		boolean stored = coordinator.complete(
				job,
				new OcrResult("오래된 OCR 본문", BigDecimal.ONE, "paddleocr", "model"),
				now.plusSeconds(1)
		);

		BookPage manuallyEdited = reload(page);
		assertThat(stored).isFalse();
		assertThat(manuallyEdited.getExtractedText()).isEqualTo("사용자 교정 본문");
		assertThat(manuallyEdited.getTextSource()).isEqualTo(TextSource.MANUAL);
	}

	private BookPage savePendingPage() {
		Book book = new Book("OCR 테스트 책", "저자");
		BookPage page = new BookPage(UUID.randomUUID(), 1, "page.png", "image/png", "object-key");
		book.addPage(page);
		bookRepository.saveAndFlush(book);
		return page;
	}

	private BookPage reload(BookPage page) {
		return bookRepository.findWithPagesById(page.getBookId()).orElseThrow().getPages().getFirst();
	}
}
