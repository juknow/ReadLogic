package com.readlogic.backend.book.api;

import com.readlogic.backend.book.domain.Book;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record BookResponse(
		UUID id,
		String title,
		String author,
		List<BookPageResponse> pages,
		Instant createdAt,
		Instant updatedAt
) {
	static BookResponse from(Book book) {
		return new BookResponse(
				book.getId(),
				book.getTitle(),
				book.getAuthor() == null ? "" : book.getAuthor(),
				book.getPages().stream()
						.sorted(Comparator.comparingInt(page -> page.getPageNumber()))
						.map(BookPageResponse::from)
						.toList(),
				book.getCreatedAt(),
				book.getUpdatedAt()
		);
	}
}

