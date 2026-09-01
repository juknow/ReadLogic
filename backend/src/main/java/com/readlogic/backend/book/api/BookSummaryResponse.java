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
		Integer firstPageNumber,
		Integer lastPageNumber,
		BookPageResponse coverPage,
		Instant createdAt,
		Instant updatedAt
) {
	static BookSummaryResponse from(Book book) {
		BookPageResponse coverPage = book.getPages().stream()
				.min(Comparator.comparingInt(page -> page.getPageNumber()))
				.map(BookPageResponse::from)
				.orElse(null);
		Integer firstPageNumber = book.getPages().stream()
				.mapToInt(page -> page.getPageNumber())
				.min()
				.stream()
				.boxed()
				.findFirst()
				.orElse(null);
		Integer lastPageNumber = book.getPages().stream()
				.mapToInt(page -> page.getPageNumber())
				.max()
				.stream()
				.boxed()
				.findFirst()
				.orElse(null);
		return new BookSummaryResponse(
				book.getId(),
				book.getTitle(),
				book.getAuthor() == null ? "" : book.getAuthor(),
				book.getPages().size(),
				firstPageNumber,
				lastPageNumber,
				coverPage,
				book.getCreatedAt(),
				book.getUpdatedAt()
		);
	}
}

