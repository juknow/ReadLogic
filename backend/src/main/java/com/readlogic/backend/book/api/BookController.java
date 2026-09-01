package com.readlogic.backend.book.api;

import com.readlogic.backend.book.application.BookApplicationService;
import com.readlogic.backend.book.application.BookApplicationService.CreateBookCommand;
import com.readlogic.backend.book.application.BookApplicationService.CreatePageCommand;
import com.readlogic.backend.book.application.BookApplicationService.PageImageUpload;
import com.readlogic.backend.storage.PageImageStorage;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

	public BookController(BookApplicationService bookService) {
		this.bookService = bookService;
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

	private PageImageUpload toUpload(MultipartFile file) {
		try {
			return new PageImageUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
		} catch (java.io.IOException exception) {
			throw new IllegalArgumentException("업로드한 이미지를 읽을 수 없습니다.", exception);
		}
	}
}
