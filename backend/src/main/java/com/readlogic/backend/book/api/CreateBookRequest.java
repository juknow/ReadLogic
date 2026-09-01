package com.readlogic.backend.book.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBookRequest(
		@NotBlank @Size(max = 255) String title,
		@Size(max = 255) String author,
		String defaultOcrLanguage,
		@NotEmpty @Valid List<CreatePageRequest> pages
) {
	public record CreatePageRequest(@Positive int pageNumber) {
	}
}

