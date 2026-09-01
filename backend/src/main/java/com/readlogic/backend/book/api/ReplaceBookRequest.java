package com.readlogic.backend.book.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ReplaceBookRequest(
		@NotBlank @Size(max = 255) String title,
		@Size(max = 255) String author,
		@NotEmpty @Valid List<PageRequest> pages
) {
	public record PageRequest(
			UUID id,
			@Positive int pageNumber,
			@PositiveOrZero Integer imageIndex
	) {
	}
}
