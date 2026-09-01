package com.readlogic.backend.book.api;

import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.OcrStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record BookPageResponse(
		UUID id,
		int pageNumber,
		String fileName,
		String mimeType,
		String extractedText,
		String ocrStatus,
		String imageUrl,
		Instant createdAt,
		Instant updatedAt
) {
	static BookPageResponse from(BookPage page) {
		return new BookPageResponse(
				page.getId(),
				page.getPageNumber(),
				page.getOriginalFileName(),
				page.getMimeType(),
				page.getExtractedText(),
				toApiStatus(page.getOcrStatus()),
				"/api/books/%s/pages/%s/image".formatted(page.getBookId(), page.getId()),
				page.getCreatedAt(),
				page.getUpdatedAt()
		);
	}

	private static String toApiStatus(OcrStatus status) {
		return status.name().toLowerCase(Locale.ROOT);
	}
}

