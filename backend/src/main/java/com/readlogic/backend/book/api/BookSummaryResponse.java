package com.readlogic.backend.book.api;

import com.readlogic.backend.book.domain.Book;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

public record BookSummaryResponse(
		UUID id,
		String title,
		String author,
		int pageCount,
		BookPageResponse coverPage,
		Instant createdAt,
		Instant updatedAt
) {
	static BookSummaryResponse from(Book book) {
		BookPageResponse coverPage = book.getPages().stream()
				.min(Comparator.comparingInt(page -> page.getPageNumber()))
				.map(BookPageResponse::from)
				.orElse(null);
		return new BookSummaryResponse(
				book.getId(),
				book.getTitle(),
				book.getAuthor() == null ? "" : book.getAuthor(),
				book.getPages().size(),
				coverPage,
				book.getCreatedAt(),
				book.getUpdatedAt()
		);
	}
}

