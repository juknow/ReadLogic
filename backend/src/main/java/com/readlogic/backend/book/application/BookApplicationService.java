package com.readlogic.backend.book.application;

import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.book.domain.OcrLanguage;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

		Book book = new Book(
				command.title().trim(),
				normalizeAuthor(command.author()),
				command.defaultOcrLanguage()
		);
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
		return updateBook(bookId, title, author, null);
	}

	@Transactional
	public Book updateBook(UUID bookId, String title, String author, OcrLanguage defaultOcrLanguage) {
		Book book = getBookWithPages(bookId);
		book.updateMetadata(
				title.trim(),
				normalizeAuthor(author),
				defaultOcrLanguage == null ? book.getDefaultOcrLanguage() : defaultOcrLanguage
		);
		return bookRepository.saveAndFlush(book);
	}

	@Transactional
	public Book replaceBook(UUID bookId, ReplaceBookCommand command, List<PageImageUpload> images) {
		Book book = getBookWithPages(bookId);
		List<PreparedPage> pages = prepareReplacement(book, command, images);
		Map<UUID, StoredUpload> storedUploads = storeReplacementImages(bookId, pages, images);

		moveChangedPagesToTemporaryNumbers(book, pages);
		List<String> replacedObjectKeys = applyReplacement(book, command, pages, storedUploads);
		Book savedBook = bookRepository.saveAndFlush(book);
		replacedObjectKeys.forEach(this::deleteImageAfterCommit);
		return savedBook;
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
	public BookPage requestPageOcr(UUID bookId, UUID pageId) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		page.requestOcr();
		book.touch();
		bookRepository.saveAndFlush(book);
		return page;
	}

	@Transactional
	public BookPage requestPageOcr(UUID bookId, UUID pageId, OcrLanguage language) {
		Book book = getBookWithPages(bookId);
		BookPage page = findPage(book, pageId);
		page.requestOcr(language);
		book.touch();
		bookRepository.saveAndFlush(book);
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

	private List<PreparedPage> prepareReplacement(
			Book book,
			ReplaceBookCommand command,
			List<PageImageUpload> images
	) {
		ensureUniquePageNumbers(command.pages().stream().map(ReplacePageCommand::pageNumber).toList());
		Map<UUID, BookPage> existingPages = book.getPages().stream()
				.collect(Collectors.toMap(BookPage::getId, Function.identity()));
		Set<UUID> requestedExistingIds = new HashSet<>();
		Set<Integer> requestedImageIndexes = new HashSet<>();
		List<PreparedPage> preparedPages = new ArrayList<>();

		for (ReplacePageCommand pageCommand : command.pages()) {
			if (pageCommand.pageNumber() < 1) {
				throw new InvalidRequestException("페이지 번호는 1 이상이어야 합니다.");
			}
			BookPage existingPage = null;
			UUID pageId = pageCommand.id();
			if (pageId == null) {
				if (pageCommand.imageIndex() == null) {
					throw new InvalidRequestException("새 페이지에는 이미지가 필요합니다.");
				}
				pageId = UUID.randomUUID();
			} else {
				existingPage = existingPages.get(pageId);
				if (existingPage == null) {
					throw new InvalidRequestException("책에 속하지 않은 페이지가 포함되어 있습니다.");
				}
				if (!requestedExistingIds.add(pageId)) {
					throw new InvalidRequestException("같은 페이지를 중복해서 수정할 수 없습니다.");
				}
			}

			Integer imageIndex = pageCommand.imageIndex();
			if (imageIndex != null) {
				if (imageIndex < 0 || imageIndex >= images.size()) {
					throw new InvalidRequestException("이미지 순서가 올바르지 않습니다.");
				}
				if (!requestedImageIndexes.add(imageIndex)) {
					throw new InvalidRequestException("같은 이미지를 여러 페이지에 사용할 수 없습니다.");
				}
			}
			preparedPages.add(new PreparedPage(pageCommand, pageId, existingPage));
		}

		if (!requestedExistingIds.equals(existingPages.keySet())) {
			throw new InvalidRequestException("기존 페이지가 모두 포함되어야 합니다.");
		}
		Set<Integer> expectedImageIndexes = IntStream.range(0, images.size())
				.boxed()
				.collect(Collectors.toSet());
		if (!requestedImageIndexes.equals(expectedImageIndexes)) {
			throw new InvalidRequestException("사용되지 않은 이미지가 포함되어 있습니다.");
		}
		return preparedPages;
	}

	private Map<UUID, StoredUpload> storeReplacementImages(
			UUID bookId,
			List<PreparedPage> pages,
			List<PageImageUpload> images
	) {
		Map<UUID, StoredUpload> storedUploads = new HashMap<>();
		for (PreparedPage page : pages) {
			Integer imageIndex = page.command().imageIndex();
			if (imageIndex == null) {
				continue;
			}
			PageImageUpload image = images.get(imageIndex);
			String objectKey = imageStorage.store(
					bookId,
					page.pageId(),
					image.contentType(),
					image.content().length,
					new ByteArrayInputStream(image.content())
			);
			deleteImageOnRollback(objectKey);
			storedUploads.put(page.pageId(), new StoredUpload(image, objectKey));
		}
		return storedUploads;
	}

	private void moveChangedPagesToTemporaryNumbers(Book book, List<PreparedPage> pages) {
		List<PreparedPage> changedPages = pages.stream()
				.filter(page -> page.existingPage() != null)
				.filter(page -> page.existingPage().getPageNumber() != page.command().pageNumber())
				.toList();
		if (changedPages.isEmpty()) {
			return;
		}
		int maximumPageNumber = pages.stream()
				.mapToInt(page -> page.command().pageNumber())
				.max()
				.orElse(0);
		maximumPageNumber = Math.max(
				maximumPageNumber,
				book.getPages().stream().mapToInt(BookPage::getPageNumber).max().orElse(0)
		);
		if ((long) maximumPageNumber + changedPages.size() > Integer.MAX_VALUE) {
			throw new InvalidRequestException("페이지 번호가 허용 범위를 초과했습니다.");
		}
		for (int index = 0; index < changedPages.size(); index++) {
			changedPages.get(index).existingPage().updateContent(maximumPageNumber + index + 1, null);
		}
		book.touch();
		bookRepository.saveAndFlush(book);
	}

	private List<String> applyReplacement(
			Book book,
			ReplaceBookCommand command,
			List<PreparedPage> pages,
			Map<UUID, StoredUpload> storedUploads
	) {
		List<String> replacedObjectKeys = new ArrayList<>();
		book.updateMetadata(
				command.title().trim(),
				normalizeAuthor(command.author()),
				command.defaultOcrLanguage() == null
						? book.getDefaultOcrLanguage()
						: command.defaultOcrLanguage()
		);
		for (PreparedPage page : pages) {
			StoredUpload storedUpload = storedUploads.get(page.pageId());
			if (page.existingPage() == null) {
				PageImageUpload image = storedUpload.image();
				book.addPage(new BookPage(
						page.pageId(),
						page.command().pageNumber(),
						image.fileName(),
						image.contentType(),
						storedUpload.objectKey()
				));
				continue;
			}

			BookPage existingPage = page.existingPage();
			existingPage.updateContent(page.command().pageNumber(), null);
			if (storedUpload != null) {
				replacedObjectKeys.add(existingPage.getObjectKey());
				PageImageUpload image = storedUpload.image();
				existingPage.replaceImage(image.fileName(), image.contentType(), storedUpload.objectKey());
			}
		}
		book.touch();
		return replacedObjectKeys;
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

	public record CreateBookCommand(
			String title,
			String author,
			OcrLanguage defaultOcrLanguage,
			List<CreatePageCommand> pages
	) {
		public CreateBookCommand(String title, String author, List<CreatePageCommand> pages) {
			this(title, author, OcrLanguage.KO, pages);
		}
	}

	public record CreatePageCommand(int pageNumber) {
	}

	public record PageImageUpload(String fileName, String contentType, byte[] content) {
	}

	public record ReplaceBookCommand(
			String title,
			String author,
			OcrLanguage defaultOcrLanguage,
			List<ReplacePageCommand> pages
	) {
		public ReplaceBookCommand(String title, String author, List<ReplacePageCommand> pages) {
			this(title, author, null, pages);
		}
	}

	public record ReplacePageCommand(UUID id, int pageNumber, Integer imageIndex) {
	}

	private record PreparedPage(ReplacePageCommand command, UUID pageId, BookPage existingPage) {
	}

	private record StoredUpload(PageImageUpload image, String objectKey) {
	}
}
