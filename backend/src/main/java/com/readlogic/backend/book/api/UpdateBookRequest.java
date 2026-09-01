package com.readlogic.backend.book.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBookRequest(
		@NotBlank @Size(max = 255) String title,
		@Size(max = 255) String author
) {
}

