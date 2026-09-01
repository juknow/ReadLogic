package com.readlogic.backend.book.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

public record UpdateBookPageRequest(
		@Positive Integer pageNumber,
		String extractedText
) {
	@AssertTrue(message = "pageNumber 또는 extractedText 중 하나는 필요합니다.")
	public boolean hasChanges() {
		return pageNumber != null || extractedText != null;
	}
}

