package com.readlogic.backend.book.application;

import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.common.error.DuplicateResourceException;
import com.readlogic.backend.common.error.InvalidRequestException;
import com.readlogic.backend.common.error.ResourceNotFoundException;
import com.readlogic.backend.storage.PageImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class BookApplicationService {

	private static final Logger log = LoggerFactory.getLogger(BookApplicationService.class);

	private final BookRepository bookRepository;
	private final PageImageStorage imageStorage;

	public BookApplicationService(BookRepository bookRepository, PageImageStorage imageStorage) {
		this.bookRepository = bookRepository;
		this.imageStorage = imageStorage;
	}

	@Transactional
	public Book createBook(CreateBookCommand command, List<PageImageUpload> images) {
		if (command.pages().size() != images.size()) {
			throw new InvalidRequestException("페이지 정보와 이미지 개수가 일치하지 않습니다.");
		}
		ensureUniquePageNumbers(command.pages().stream().map(CreatePageCommand::pageNumber).toList());

		Book book = new Book(command.title().trim(), normalizeAuthor(command.author()));
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
			deleteImageOnRollback(objectKey);
			book.addPage(new BookPage(
					pageId,
					pageCommand.pageNumber(),
					image.fileName(),
					image.contentType(),
					objectKey
			));
		}
		return bookRepository.saveAndFlush(book);
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
		List<String> objectKeys = book.getPages().stream().map(BookPage::getObjectKey).toList();
		bookRepository.delete(book);
		bookRepository.flush();
		objectKeys.forEach(this::deleteImageAfterCommit);
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
		deleteImageOnRollback(objectKey);
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
		deleteImageOnRollback(nextObjectKey);
		page.replaceImage(image.fileName(), image.contentType(), nextObjectKey);
		book.touch();
		bookRepository.saveAndFlush(book);
		deleteImageAfterCommit(previousObjectKey);
		return page;
	}

	@Transactional
	public void deletePage(UUID bookId, UUID pageId) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		String objectKey = page.getObjectKey();
		book.removePage(page);
		bookRepository.saveAndFlush(book);
		deleteImageAfterCommit(objectKey);
	}

	private Book getBookWithPages(UUID bookId) {
		return bookRepository.findWithPagesById(bookId)
				.orElseThrow(() -> new ResourceNotFoundException("책을 찾을 수 없습니다."));
	}

	private BookPage findPage(Book book, UUID pageId) {
		return book.getPages().stream()
				.filter(page -> page.getId().equals(pageId))
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("페이지를 찾을 수 없습니다."));
	}

	private void ensureUniquePageNumbers(List<Integer> pageNumbers) {
		if (new HashSet<>(pageNumbers).size() != pageNumbers.size()) {
			throw new DuplicateResourceException("한 책에서 페이지 번호는 중복될 수 없습니다.");
		}
	}

	private void ensurePageNumberAvailable(Book book, int pageNumber, UUID ignoredPageId) {
		boolean duplicate = book.getPages().stream()
				.anyMatch(page -> page.getPageNumber() == pageNumber && !page.getId().equals(ignoredPageId));
		if (duplicate) {
			throw new DuplicateResourceException("한 책에서 페이지 번호는 중복될 수 없습니다.");
		}
	}

	private String normalizeAuthor(String author) {
		return author == null || author.isBlank() ? null : author.trim();
	}

	private void deleteImageOnRollback(String objectKey) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					safelyDeleteImage(objectKey);
				}
			}
		});
	}

	private void deleteImageAfterCommit(String objectKey) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safelyDeleteImage(objectKey);
			}
		});
	}

	private void safelyDeleteImage(String objectKey) {
		try {
			imageStorage.delete(objectKey);
		} catch (RuntimeException exception) {
			log.error("MinIO 이미지 정리에 실패했습니다. objectKey={}", objectKey, exception);
		}
	}

	public record CreateBookCommand(String title, String author, List<CreatePageCommand> pages) {
	}

	public record CreatePageCommand(int pageNumber) {
	}

	public record PageImageUpload(String fileName, String contentType, byte[] content) {
	}
}
