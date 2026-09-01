package com.readlogic.backend.book.api;

import com.readlogic.backend.book.application.BookApplicationService;
import com.readlogic.backend.book.application.BookApplicationService.CreateBookCommand;
import com.readlogic.backend.book.application.BookApplicationService.CreatePageCommand;
import com.readlogic.backend.book.application.BookApplicationService.PageImageUpload;
import com.readlogic.backend.storage.PageImageStorage;
import com.readlogic.backend.common.error.InvalidRequestException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookController {

	private final BookApplicationService bookService;
	private final ImageUploadValidator imageValidator;

	public BookController(BookApplicationService bookService, ImageUploadValidator imageValidator) {
		this.bookService = bookService;
		this.imageValidator = imageValidator;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<BookResponse> createBook(
			@RequestPart("metadata") @Valid CreateBookRequest request,
			@RequestPart("images") List<MultipartFile> images
	) {
		CreateBookCommand command = new CreateBookCommand(
				request.title(),
				request.author(),
				request.pages().stream()
						.map(page -> new CreatePageCommand(page.pageNumber()))
						.toList()
		);
		List<PageImageUpload> uploads = images.stream()
				.map(this::toUpload)
				.toList();
		BookResponse response = BookResponse.from(bookService.createBook(command, uploads));
		return ResponseEntity.created(URI.create("/api/books/" + response.id())).body(response);
	}

	@GetMapping
	public List<BookSummaryResponse> getBooks() {
		return bookService.getBooks().stream().map(BookSummaryResponse::from).toList();
	}

	@GetMapping("/{bookId}")
	public BookResponse getBook(@PathVariable UUID bookId) {
		return BookResponse.from(bookService.getBook(bookId));
	}

	@GetMapping("/{bookId}/pages/{pageId}/image")
	public ResponseEntity<byte[]> getPageImage(@PathVariable UUID bookId, @PathVariable UUID pageId) {
		PageImageStorage.StoredImage image = bookService.getPageImage(bookId, pageId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
				.contentType(MediaType.parseMediaType(image.contentType()))
				.body(image.content());
	}

	@PatchMapping("/{bookId}")
	public BookResponse updateBook(@PathVariable UUID bookId, @RequestBody @Valid UpdateBookRequest request) {
		return BookResponse.from(bookService.updateBook(bookId, request.title(), request.author()));
	}

	@PutMapping(path = "/{bookId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BookResponse replaceBook(
			@PathVariable UUID bookId,
			@RequestPart("metadata") @Valid ReplaceBookRequest request,
			@RequestPart(value = "images", required = false) List<MultipartFile> images
	) {
		List<PageImageUpload> uploads = images == null
				? List.of()
				: images.stream().map(this::toUpload).toList();
		BookApplicationService.ReplaceBookCommand command = new BookApplicationService.ReplaceBookCommand(
				request.title(),
				request.author(),
				request.pages().stream()
						.map(page -> new BookApplicationService.ReplacePageCommand(
								page.id(),
								page.pageNumber(),
								page.imageIndex()
						))
						.toList()
		);
		return BookResponse.from(bookService.replaceBook(bookId, command, uploads));
	}

	@DeleteMapping("/{bookId}")
	public ResponseEntity<Void> deleteBook(@PathVariable UUID bookId) {
		bookService.deleteBook(bookId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/{bookId}/pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<BookPageResponse> addPage(
			@PathVariable UUID bookId,
			@RequestPart("metadata") @Valid AddBookPageRequest request,
			@RequestPart("image") MultipartFile image
	) {
		BookPageResponse response = BookPageResponse.from(
				bookService.addPage(bookId, request.pageNumber(), toUpload(image))
		);
		return ResponseEntity.created(URI.create(response.imageUrl())).body(response);
	}

	@PatchMapping("/{bookId}/pages/{pageId}")
	public BookPageResponse updatePage(
			@PathVariable UUID bookId,
			@PathVariable UUID pageId,
			@RequestBody @Valid UpdateBookPageRequest request
	) {
		return BookPageResponse.from(
				bookService.updatePage(bookId, pageId, request.pageNumber(), request.extractedText())
		);
	}

	@PutMapping(path = "/{bookId}/pages/{pageId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BookPageResponse replacePageImage(
			@PathVariable UUID bookId,
			@PathVariable UUID pageId,
			@RequestPart("image") MultipartFile image
	) {
		return BookPageResponse.from(bookService.replacePageImage(bookId, pageId, toUpload(image)));
	}

	@PostMapping("/{bookId}/pages/{pageId}/ocr")
	public ResponseEntity<BookPageResponse> requestPageOcr(
			@PathVariable UUID bookId,
			@PathVariable UUID pageId
	) {
		BookPageResponse response = BookPageResponse.from(bookService.requestPageOcr(bookId, pageId));
		return ResponseEntity.accepted().body(response);
	}

	@DeleteMapping("/{bookId}/pages/{pageId}")
	public ResponseEntity<Void> deletePage(@PathVariable UUID bookId, @PathVariable UUID pageId) {
		bookService.deletePage(bookId, pageId);
		return ResponseEntity.noContent().build();
	}

	private PageImageUpload toUpload(MultipartFile file) {
		imageValidator.validate(file);
		try {
			return new PageImageUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
		} catch (java.io.IOException exception) {
			throw new InvalidRequestException("업로드한 이미지를 읽을 수 없습니다.", exception);
		}
	}
}
