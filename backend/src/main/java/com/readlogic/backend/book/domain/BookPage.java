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

import java.time.Instant;
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

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BookPage() {
	}

	public BookPage(int pageNumber, String originalFileName, String mimeType, String objectKey) {
		this.id = UUID.randomUUID();
		this.pageNumber = pageNumber;
		this.originalFileName = originalFileName;
		this.mimeType = mimeType;
		this.objectKey = objectKey;
		this.extractedText = "";
		this.ocrStatus = OcrStatus.PENDING;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
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
			this.extractedText = extractedText;
			this.ocrStatus = extractedText.isBlank() ? OcrStatus.PENDING : OcrStatus.READY;
		}
	}

	public void replaceImage(String originalFileName, String mimeType, String objectKey) {
		this.originalFileName = originalFileName;
		this.mimeType = mimeType;
		this.objectKey = objectKey;
		this.extractedText = "";
		this.ocrStatus = OcrStatus.PENDING;
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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}

