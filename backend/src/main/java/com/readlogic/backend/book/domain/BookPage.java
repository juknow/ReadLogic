package com.readlogic.backend.book.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "book_pages")
public class BookPage {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "book_id", nullable = false)
	private Book book;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(name = "original_file_name", nullable = false, length = 512)
	private String originalFileName;

	@Column(name = "mime_type", nullable = false, length = 100)
	private String mimeType;

	@Column(name = "object_key", nullable = false, length = 1024)
	private String objectKey;

	@Column(name = "extracted_text", nullable = false, columnDefinition = "TEXT")
	private String extractedText;

	@Enumerated(EnumType.STRING)
	@Column(name = "ocr_status", nullable = false, length = 20)
	private OcrStatus ocrStatus;

	@Column(name = "ocr_revision", nullable = false)
	private int ocrRevision;

	@Column(name = "ocr_attempt_count", nullable = false)
	private int ocrAttemptCount;

	@Column(name = "ocr_confidence", precision = 5, scale = 4)
	private BigDecimal ocrConfidence;

	@Column(name = "ocr_engine", length = 50)
	private String ocrEngine;

	@Column(name = "ocr_model", length = 100)
	private String ocrModel;

	@Enumerated(EnumType.STRING)
	@Column(name = "ocr_language", length = 10)
	private OcrLanguage ocrLanguage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ocr_document", columnDefinition = "jsonb")
	private Map<String, Object> ocrDocument;

	@Column(name = "ocr_last_error_code", length = 100)
	private String ocrLastErrorCode;

	@Column(name = "ocr_last_error_message", columnDefinition = "TEXT")
	private String ocrLastErrorMessage;

	@Column(name = "ocr_requested_at")
	private Instant ocrRequestedAt;

	@Column(name = "ocr_started_at")
	private Instant ocrStartedAt;

	@Column(name = "ocr_completed_at")
	private Instant ocrCompletedAt;

	@Column(name = "ocr_next_attempt_at")
	private Instant ocrNextAttemptAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "text_source", nullable = false, length = 20)
	private TextSource textSource;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BookPage() {
	}

	public BookPage(UUID id, int pageNumber, String originalFileName, String mimeType, String objectKey) {
		this.id = id;
		this.pageNumber = pageNumber;
		this.originalFileName = originalFileName;
		this.mimeType = mimeType;
		this.objectKey = objectKey;
		this.extractedText = "";
		this.ocrStatus = OcrStatus.PENDING;
		this.ocrRevision = 0;
		this.ocrAttemptCount = 0;
		this.textSource = TextSource.NONE;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (ocrStatus == OcrStatus.PENDING && ocrRequestedAt == null) {
			ocrRequestedAt = now;
			ocrNextAttemptAt = now;
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	void attachTo(Book book) {
		this.book = book;
	}

	void detach() {
		this.book = null;
	}

	public void updateContent(Integer pageNumber, String extractedText) {
		if (pageNumber != null) {
			this.pageNumber = pageNumber;
		}
		if (extractedText != null) {
			applyManualText(extractedText);
		}
	}

	public void applyManualText(String extractedText) {
		this.extractedText = extractedText;
		this.ocrRevision++;
		this.ocrStatus = OcrStatus.READY;
		this.textSource = TextSource.MANUAL;
		this.ocrConfidence = null;
		this.ocrEngine = null;
		this.ocrModel = null;
		this.ocrDocument = null;
		this.ocrLastErrorCode = null;
		this.ocrLastErrorMessage = null;
		this.ocrStartedAt = null;
		this.ocrCompletedAt = Instant.now();
		this.ocrNextAttemptAt = null;
	}

	public void replaceImage(String originalFileName, String mimeType, String objectKey) {
		this.originalFileName = originalFileName;
		this.mimeType = mimeType;
		this.objectKey = objectKey;
		this.ocrRevision++;
		resetForOcr();
	}

	public void requestOcr() {
		if (ocrStatus == OcrStatus.PENDING || ocrStatus == OcrStatus.PROCESSING) {
			return;
		}
		ocrRevision++;
		resetForOcr();
	}

	public void requestOcr(OcrLanguage language) {
		if (ocrLanguage == language && (ocrStatus == OcrStatus.PENDING || ocrStatus == OcrStatus.PROCESSING)) {
			return;
		}
		ocrLanguage = language;
		ocrRevision++;
		resetForOcr();
	}

	public boolean claimOcr(Instant startedAt) {
		if (ocrStatus != OcrStatus.PENDING
				|| (ocrNextAttemptAt != null && ocrNextAttemptAt.isAfter(startedAt))) {
			return false;
		}
		ocrStatus = OcrStatus.PROCESSING;
		ocrAttemptCount++;
		ocrStartedAt = startedAt;
		ocrNextAttemptAt = null;
		ocrLastErrorCode = null;
		ocrLastErrorMessage = null;
		return true;
	}

	public boolean completeOcr(
			int expectedRevision,
			String text,
			BigDecimal confidence,
			String engine,
			String model,
			Map<String, Object> document,
			Instant completedAt
	) {
		if (!isCurrentProcessing(expectedRevision)) {
			return false;
		}
		extractedText = text;
		ocrStatus = OcrStatus.READY;
		ocrConfidence = confidence;
		ocrEngine = engine;
		ocrModel = model;
		ocrDocument = document;
		ocrLastErrorCode = null;
		ocrLastErrorMessage = null;
		ocrCompletedAt = completedAt;
		ocrNextAttemptAt = null;
		textSource = TextSource.OCR;
		return true;
	}

	public boolean scheduleOcrRetry(
			int expectedRevision,
			String errorCode,
			String errorMessage,
			Instant nextAttemptAt
	) {
		if (!isCurrentProcessing(expectedRevision)) {
			return false;
		}
		ocrStatus = OcrStatus.PENDING;
		ocrLastErrorCode = errorCode;
		ocrLastErrorMessage = errorMessage;
		ocrStartedAt = null;
		ocrNextAttemptAt = nextAttemptAt;
		return true;
	}

	public boolean failOcr(
			int expectedRevision,
			String errorCode,
			String errorMessage,
			Instant failedAt
	) {
		if (!isCurrentProcessing(expectedRevision)) {
			return false;
		}
		ocrStatus = OcrStatus.FAILED;
		ocrLastErrorCode = errorCode;
		ocrLastErrorMessage = errorMessage;
		ocrCompletedAt = failedAt;
		ocrNextAttemptAt = null;
		return true;
	}

	public boolean recoverStaleOcr(Instant startedBefore, Instant nextAttemptAt) {
		if (ocrStatus != OcrStatus.PROCESSING
				|| ocrStartedAt == null
				|| ocrStartedAt.isAfter(startedBefore)) {
			return false;
		}
		ocrStatus = OcrStatus.PENDING;
		ocrStartedAt = null;
		ocrNextAttemptAt = nextAttemptAt;
		ocrLastErrorCode = "OCR_STALE_JOB_RECOVERED";
		ocrLastErrorMessage = "중단된 OCR 작업을 다시 대기 상태로 전환했습니다.";
		return true;
	}

	private boolean isCurrentProcessing(int expectedRevision) {
		return ocrStatus == OcrStatus.PROCESSING && ocrRevision == expectedRevision;
	}

	private void resetForOcr() {
		Instant now = Instant.now();
		this.extractedText = "";
		this.ocrStatus = OcrStatus.PENDING;
		this.ocrAttemptCount = 0;
		this.ocrConfidence = null;
		this.ocrEngine = null;
		this.ocrModel = null;
		this.ocrDocument = null;
		this.ocrLastErrorCode = null;
		this.ocrLastErrorMessage = null;
		this.ocrRequestedAt = now;
		this.ocrStartedAt = null;
		this.ocrCompletedAt = null;
		this.ocrNextAttemptAt = now;
		this.textSource = TextSource.NONE;
	}

	public UUID getId() {
		return id;
	}

	public UUID getBookId() {
		return book.getId();
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public String getOriginalFileName() {
		return originalFileName;
	}

	public String getMimeType() {
		return mimeType;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public String getExtractedText() {
		return extractedText;
	}

	public OcrStatus getOcrStatus() {
		return ocrStatus;
	}

	public int getOcrRevision() {
		return ocrRevision;
	}

	public int getOcrAttemptCount() {
		return ocrAttemptCount;
	}

	public BigDecimal getOcrConfidence() {
		return ocrConfidence;
	}

	public String getOcrEngine() {
		return ocrEngine;
	}

	public String getOcrModel() {
		return ocrModel;
	}

	public OcrLanguage getOcrLanguage() {
		return ocrLanguage;
	}

	public OcrLanguage getEffectiveOcrLanguage() {
		return ocrLanguage == null ? book.getDefaultOcrLanguage() : ocrLanguage;
	}

	public Map<String, Object> getOcrDocument() {
		return ocrDocument;
	}

	public String getOcrLastErrorCode() {
		return ocrLastErrorCode;
	}

	public String getOcrLastErrorMessage() {
		return ocrLastErrorMessage;
	}

	public Instant getOcrRequestedAt() {
		return ocrRequestedAt;
	}

	public Instant getOcrStartedAt() {
		return ocrStartedAt;
	}

	public Instant getOcrCompletedAt() {
		return ocrCompletedAt;
	}

	public Instant getOcrNextAttemptAt() {
		return ocrNextAttemptAt;
	}

	public TextSource getTextSource() {
		return textSource;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
