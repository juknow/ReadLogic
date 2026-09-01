package com.readlogic.backend.book.application;

import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.storage.PageImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class BookApplicationService {

	private final BookRepository bookRepository;
	private final PageImageStorage imageStorage;

	public BookApplicationService(BookRepository bookRepository, PageImageStorage imageStorage) {
		this.bookRepository = bookRepository;
		this.imageStorage = imageStorage;
	}

	@Transactional
	public Book createBook(CreateBookCommand command, List<PageImageUpload> images) {
		if (command.pages().size() != images.size()) {
			throw new IllegalArgumentException("페이지 정보와 이미지 개수가 일치하지 않습니다.");
		}
		ensureUniquePageNumbers(command.pages().stream().map(CreatePageCommand::pageNumber).toList());

		Book book = new Book(command.title().trim(), normalizeAuthor(command.author()));
		List<String> storedObjectKeys = new ArrayList<>();
		try {
			for (int index = 0; index < command.pages().size(); index++) {
				CreatePageCommand pageCommand = command.pages().get(index);
				PageImageUpload image = images.get(index);
				UUID pageId = UUID.randomUUID();
				String objectKey = imageStorage.store(
						book.getId(),
						pageId,
						image.contentType(),
						image.content().length,
						new ByteArrayInputStream(image.content())
				);
				storedObjectKeys.add(objectKey);
				book.addPage(new BookPage(
						pageId,
						pageCommand.pageNumber(),
						image.fileName(),
						image.contentType(),
						objectKey
				));
			}
			return bookRepository.saveAndFlush(book);
		} catch (RuntimeException exception) {
			cleanupStoredImages(storedObjectKeys, exception);
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public List<Book> getBooks() {
		return bookRepository.findAllByOrderByCreatedAtDesc();
	}

	@Transactional(readOnly = true)
	public Book getBook(UUID bookId) {
		return getBookWithPages(bookId);
	}

	@Transactional(readOnly = true)
	public PageImageStorage.StoredImage getPageImage(UUID bookId, UUID pageId) {
		BookPage page = findPage(getBookWithPages(bookId), pageId);
		return imageStorage.load(page.getObjectKey());
	}

	@Transactional
	public Book updateBook(UUID bookId, String title, String author) {
		Book book = getBookWithPages(bookId);
		book.updateMetadata(title.trim(), normalizeAuthor(author));
		return bookRepository.saveAndFlush(book);
	}

	@Transactional
	public void deleteBook(UUID bookId) {
		Book book = getBookWithPages(bookId);
		book.getPages().forEach(page -> imageStorage.delete(page.getObjectKey()));
		bookRepository.delete(book);
		bookRepository.flush();
	}

	@Transactional
	public BookPage addPage(UUID bookId, int pageNumber, PageImageUpload image) {
		Book book = getBookWithPages(bookId);
		ensurePageNumberAvailable(book, pageNumber, null);
		UUID pageId = UUID.randomUUID();
		String objectKey = imageStorage.store(
				bookId,
				pageId,
				image.contentType(),
				image.content().length,
				new ByteArrayInputStream(image.content())
		);
		try {
			BookPage page = new BookPage(
					pageId,
					pageNumber,
					image.fileName(),
					image.contentType(),
					objectKey
			);
			book.addPage(page);
			bookRepository.saveAndFlush(book);
			return page;
		} catch (RuntimeException exception) {
			cleanupStoredImages(List.of(objectKey), exception);
			throw exception;
		}
	}

	@Transactional
	public BookPage updatePage(UUID bookId, UUID pageId, Integer pageNumber, String extractedText) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		if (pageNumber != null) {
			ensurePageNumberAvailable(book, pageNumber, pageId);
		}
		page.updateContent(pageNumber, extractedText);
		book.touch();
		bookRepository.saveAndFlush(book);
		return page;
	}

	@Transactional
	public BookPage replacePageImage(UUID bookId, UUID pageId, PageImageUpload image) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		String previousObjectKey = page.getObjectKey();
		String nextObjectKey = imageStorage.store(
				bookId,
				pageId,
				image.contentType(),
				image.content().length,
				new ByteArrayInputStream(image.content())
		);
		try {
			page.replaceImage(image.fileName(), image.contentType(), nextObjectKey);
			book.touch();
			bookRepository.saveAndFlush(book);
		} catch (RuntimeException exception) {
			cleanupStoredImages(List.of(nextObjectKey), exception);
			throw exception;
		}
		imageStorage.delete(previousObjectKey);
		return page;
	}

	@Transactional
	public void deletePage(UUID bookId, UUID pageId) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		imageStorage.delete(page.getObjectKey());
		book.removePage(page);
		bookRepository.saveAndFlush(book);
	}

	private Book getBookWithPages(UUID bookId) {
		return bookRepository.findWithPagesById(bookId)
				.orElseThrow(() -> new NoSuchElementException("책을 찾을 수 없습니다."));
	}

	private BookPage findPage(Book book, UUID pageId) {
		return book.getPages().stream()
				.filter(page -> page.getId().equals(pageId))
				.findFirst()
				.orElseThrow(() -> new NoSuchElementException("페이지를 찾을 수 없습니다."));
	}

	private void ensureUniquePageNumbers(List<Integer> pageNumbers) {
		if (new HashSet<>(pageNumbers).size() != pageNumbers.size()) {
			throw new IllegalArgumentException("한 책에서 페이지 번호는 중복될 수 없습니다.");
		}
	}

	private void ensurePageNumberAvailable(Book book, int pageNumber, UUID ignoredPageId) {
		boolean duplicate = book.getPages().stream()
				.anyMatch(page -> page.getPageNumber() == pageNumber && !page.getId().equals(ignoredPageId));
		if (duplicate) {
			throw new IllegalArgumentException("한 책에서 페이지 번호는 중복될 수 없습니다.");
		}
	}

	private String normalizeAuthor(String author) {
		return author == null || author.isBlank() ? null : author.trim();
	}

	private void cleanupStoredImages(List<String> objectKeys, RuntimeException originalException) {
		for (String objectKey : objectKeys) {
			try {
				imageStorage.delete(objectKey);
			} catch (RuntimeException cleanupException) {
				originalException.addSuppressed(cleanupException);
			}
		}
	}

	public record CreateBookCommand(String title, String author, List<CreatePageCommand> pages) {
	}

	public record CreatePageCommand(int pageNumber) {
	}

	public record PageImageUpload(String fileName, String contentType, byte[] content) {
	}
}
