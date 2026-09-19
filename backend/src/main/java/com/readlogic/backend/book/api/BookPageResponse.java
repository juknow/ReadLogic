package com.readlogic.backend.book.api;

import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.OcrStatus;
import com.readlogic.backend.book.domain.TextSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public record BookPageResponse(
		UUID id,
		int pageNumber,
		String fileName,
		String mimeType,
		String extractedText,
		String ocrStatus,
		BigDecimal ocrConfidence,
		String ocrEngine,
		String ocrModel,
		String ocrLanguage,
		Map<String, Object> ocrDocument,
		String ocrErrorCode,
		String ocrErrorMessage,
		Instant ocrRequestedAt,
		Instant ocrCompletedAt,
		String textSource,
		String imageUrl,
		Instant createdAt,
		Instant updatedAt
) {
	static BookPageResponse from(BookPage page) {
		return from(page, true);
	}

	static BookPageResponse summaryFrom(BookPage page) {
		return from(page, false);
	}

	private static BookPageResponse from(BookPage page, boolean includeDocument) {
		return new BookPageResponse(
				page.getId(),
				page.getPageNumber(),
				page.getOriginalFileName(),
				page.getMimeType(),
				page.getExtractedText(),
				toApiStatus(page.getOcrStatus()),
				page.getOcrConfidence(),
				page.getOcrEngine(),
				page.getOcrModel(),
				page.getOcrLanguage() == null ? null : page.getOcrLanguage().apiValue(),
				includeDocument ? page.getOcrDocument() : null,
				page.getOcrLastErrorCode(),
				page.getOcrLastErrorMessage(),
				page.getOcrRequestedAt(),
				page.getOcrCompletedAt(),
				toApiTextSource(page.getTextSource()),
				"/api/books/%s/pages/%s/image".formatted(page.getBookId(), page.getId()),
				page.getCreatedAt(),
				page.getUpdatedAt()
		);
	}

	private static String toApiStatus(OcrStatus status) {
		return status.name().toLowerCase(Locale.ROOT);
	}

	private static String toApiTextSource(TextSource source) {
		return source.name().toLowerCase(Locale.ROOT);
	}
}

