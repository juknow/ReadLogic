package com.readlogic.backend.book.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BookPageRepository extends JpaRepository<BookPage, UUID> {

	@Query(value = """
			SELECT *
			FROM book_pages
			WHERE ocr_status = 'PENDING'
			  AND (ocr_next_attempt_at IS NULL OR ocr_next_attempt_at <= :now)
			ORDER BY ocr_next_attempt_at NULLS FIRST, created_at, id
			LIMIT :batchSize
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<BookPage> lockPendingOcrPages(@Param("now") Instant now, @Param("batchSize") int batchSize);

	@Query(value = """
			SELECT *
			FROM book_pages
			WHERE ocr_status = 'PROCESSING'
			  AND ocr_started_at <= :startedBefore
			ORDER BY ocr_started_at, id
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<BookPage> lockStaleOcrPages(@Param("startedBefore") Instant startedBefore);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE BookPage page
			SET page.extractedText = :text,
			    page.ocrStatus = :readyStatus,
			    page.ocrConfidence = :confidence,
			    page.ocrEngine = :engine,
			    page.ocrModel = :model,
			    page.ocrDocument = :document,
			    page.ocrLastErrorCode = null,
			    page.ocrLastErrorMessage = null,
			    page.ocrCompletedAt = :completedAt,
			    page.ocrNextAttemptAt = null,
			    page.textSource = :textSource,
			    page.updatedAt = :completedAt
			WHERE page.id = :pageId
			  AND page.ocrStatus = :processingStatus
			  AND page.ocrRevision = :revision
			""")
	int completeOcr(
			@Param("pageId") UUID pageId,
			@Param("revision") int revision,
			@Param("text") String text,
			@Param("confidence") BigDecimal confidence,
			@Param("engine") String engine,
			@Param("model") String model,
			@Param("document") Map<String, Object> document,
			@Param("completedAt") Instant completedAt,
			@Param("readyStatus") OcrStatus readyStatus,
			@Param("processingStatus") OcrStatus processingStatus,
			@Param("textSource") TextSource textSource
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE BookPage page
			SET page.ocrStatus = :pendingStatus,
			    page.ocrLastErrorCode = :errorCode,
			    page.ocrLastErrorMessage = :errorMessage,
			    page.ocrStartedAt = null,
			    page.ocrNextAttemptAt = :nextAttemptAt,
			    page.updatedAt = :failedAt
			WHERE page.id = :pageId
			  AND page.ocrStatus = :processingStatus
			  AND page.ocrRevision = :revision
			""")
	int scheduleOcrRetry(
			@Param("pageId") UUID pageId,
			@Param("revision") int revision,
			@Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage,
			@Param("failedAt") Instant failedAt,
			@Param("nextAttemptAt") Instant nextAttemptAt,
			@Param("pendingStatus") OcrStatus pendingStatus,
			@Param("processingStatus") OcrStatus processingStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE BookPage page
			SET page.ocrStatus = :failedStatus,
			    page.ocrLastErrorCode = :errorCode,
			    page.ocrLastErrorMessage = :errorMessage,
			    page.ocrCompletedAt = :failedAt,
			    page.ocrNextAttemptAt = null,
			    page.updatedAt = :failedAt
			WHERE page.id = :pageId
			  AND page.ocrStatus = :processingStatus
			  AND page.ocrRevision = :revision
			""")
	int failOcr(
			@Param("pageId") UUID pageId,
			@Param("revision") int revision,
			@Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage,
			@Param("failedAt") Instant failedAt,
			@Param("failedStatus") OcrStatus failedStatus,
			@Param("processingStatus") OcrStatus processingStatus
	);
}
