package com.readlogic.backend.book;

import com.readlogic.backend.book.application.BookApplicationService;
import com.readlogic.backend.book.application.BookApplicationService.CreateBookCommand;
import com.readlogic.backend.book.application.BookApplicationService.CreatePageCommand;
import com.readlogic.backend.book.application.BookApplicationService.PageImageUpload;
import com.readlogic.backend.book.application.BookApplicationService.ReplaceBookCommand;
import com.readlogic.backend.book.application.BookApplicationService.ReplacePageCommand;
import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.storage.ImageStorageException;
import com.readlogic.backend.storage.PageImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class BookApplicationServiceTests {

	@Autowired
	private BookApplicationService bookService;

	@Autowired
	private BookRepository bookRepository;

	@MockitoBean
	private PageImageStorage imageStorage;

	@BeforeEach
	void setUp() {
		bookRepository.deleteAll();
		reset(imageStorage);
	}

	@Test
	void removesAlreadyStoredImagesWhenBookCreationFails() {
		when(imageStorage.store(any(), any(), any(), anyLong(), any()))
				.thenReturn("stored-first")
				.thenThrow(new ImageStorageException("storage failure", new RuntimeException()));

		CreateBookCommand command = new CreateBookCommand(
				"실패하는 책",
				"저자",
				List.of(new CreatePageCommand(1), new CreatePageCommand(2))
		);
		List<PageImageUpload> images = List.of(
				new PageImageUpload("first.png", "image/png", new byte[]{1}),
				new PageImageUpload("second.png", "image/png", new byte[]{2})
		);

		assertThatThrownBy(() -> bookService.createBook(command, images))
				.isInstanceOf(ImageStorageException.class);

		verify(imageStorage).delete("stored-first");
		assertThat(bookRepository.count()).isZero();
	}

	@Test
	void rollsBackAggregateReplacementWhenImageStorageFails() {
		Book book = new Book("원래 책", "원래 저자");
		BookPage firstPage = new BookPage(UUID.randomUUID(), 1, "first.png", "image/png", "old-first");
		BookPage secondPage = new BookPage(UUID.randomUUID(), 2, "second.png", "image/png", "old-second");
		book.addPage(firstPage);
		book.addPage(secondPage);
		bookRepository.saveAndFlush(book);
		reset(imageStorage);
		when(imageStorage.store(any(), any(), any(), anyLong(), any()))
				.thenReturn("new-first")
				.thenThrow(new ImageStorageException("storage failure", new RuntimeException()));

		ReplaceBookCommand command = new ReplaceBookCommand(
				"수정할 책",
				"수정할 저자",
				List.of(
						new ReplacePageCommand(firstPage.getId(), 2, 0),
						new ReplacePageCommand(secondPage.getId(), 1, null),
						new ReplacePageCommand(null, 3, 1)
				)
		);
		List<PageImageUpload> images = List.of(
				new PageImageUpload("replacement.png", "image/png", new byte[]{1}),
				new PageImageUpload("third.png", "image/png", new byte[]{2})
		);

		assertThatThrownBy(() -> bookService.replaceBook(book.getId(), command, images))
				.isInstanceOf(ImageStorageException.class);

		verify(imageStorage).delete("new-first");
		Book unchangedBook = bookRepository.findWithPagesById(book.getId()).orElseThrow();
		assertThat(unchangedBook.getTitle()).isEqualTo("원래 책");
		assertThat(unchangedBook.getPages())
				.extracting(BookPage::getPageNumber)
				.containsExactly(1, 2);
		assertThat(unchangedBook.getPages())
				.extracting(BookPage::getObjectKey)
				.containsExactly("old-first", "old-second");
	}
}

