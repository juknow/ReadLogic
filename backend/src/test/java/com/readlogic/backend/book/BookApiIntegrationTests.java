package com.readlogic.backend.book;

import com.readlogic.backend.book.domain.Book;
import com.readlogic.backend.book.domain.BookPage;
import com.readlogic.backend.book.domain.BookRepository;
import com.readlogic.backend.storage.PageImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BookApiIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

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
	void createsListsAndReadsABook() throws Exception {
		when(imageStorage.store(any(), any(), eq("image/png"), anyLong(), any()))
				.thenReturn("books/book/pages/first", "books/book/pages/second");

		mockMvc.perform(createBookRequest())
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.title").value("테스트 책"))
				.andExpect(jsonPath("$.author").value("테스트 저자"))
				.andExpect(jsonPath("$.pages[0].pageNumber").value(1))
				.andExpect(jsonPath("$.pages[0].ocrStatus").value("pending"))
				.andExpect(jsonPath("$.pages[1].pageNumber").value(2));

		Book savedBook = bookRepository.findAllByOrderByCreatedAtDesc().getFirst();
		mockMvc.perform(get("/api/books"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(savedBook.getId().toString()))
				.andExpect(jsonPath("$[0].pageCount").value(2))
				.andExpect(jsonPath("$[0].coverPage.pageNumber").value(1));

		mockMvc.perform(get("/api/books/{bookId}", savedBook.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pages.length()").value(2));
	}

	@Test
	void editsPagesAndRemovesTheirImages() throws Exception {
		when(imageStorage.store(any(), any(), eq("image/png"), anyLong(), any()))
				.thenReturn("object-1", "object-2", "object-3", "object-4");
		mockMvc.perform(createBookRequest()).andExpect(status().isCreated());

		Book book = bookRepository.findAllByOrderByCreatedAtDesc().getFirst();
		BookPage firstPage = book.getPages().getFirst();

		mockMvc.perform(patch("/api/books/{bookId}", book.getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"title":"수정된 책","author":"수정된 저자"}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("수정된 책"));

		mockMvc.perform(addPageRequest(book.getId()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.pageNumber").value(3));

		mockMvc.perform(patch("/api/books/{bookId}/pages/{pageId}", book.getId(), firstPage.getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"pageNumber":4,"extractedText":"추출된 문장"}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pageNumber").value(4))
				.andExpect(jsonPath("$.ocrStatus").value("ready"));

		mockMvc.perform(replaceImageRequest(book.getId(), firstPage.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("replacement.png"))
				.andExpect(jsonPath("$.ocrStatus").value("pending"));
		verify(imageStorage).delete("object-1");

		mockMvc.perform(delete("/api/books/{bookId}/pages/{pageId}", book.getId(), firstPage.getId()))
				.andExpect(status().isNoContent());
		verify(imageStorage).delete("object-4");

		mockMvc.perform(delete("/api/books/{bookId}", book.getId()))
				.andExpect(status().isNoContent());
		assertThat(bookRepository.existsById(book.getId())).isFalse();
	}

	@Test
	void returnsExpectedErrorsForInvalidRequests() throws Exception {
		mockMvc.perform(get("/api/books/{bookId}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.fieldErrors").isMap());

		MockMultipartHttpServletRequestBuilder request = multipart("/api/books")
				.file(jsonPart("metadata", """
						{"title":"중복","author":"","pages":[{"pageNumber":1},{"pageNumber":1}]}
						"""))
				.file(imagePart("images", "first.png"))
				.file(imagePart("images", "second.png"));
		mockMvc.perform(request)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PAGE_NUMBER"));
	}

	private MockMultipartHttpServletRequestBuilder createBookRequest() {
		return multipart("/api/books")
				.file(jsonPart("metadata", """
						{"title":"테스트 책","author":"테스트 저자","pages":[{"pageNumber":1},{"pageNumber":2}]}
						"""))
				.file(imagePart("images", "first.png"))
				.file(imagePart("images", "second.png"));
	}

	private MockMultipartHttpServletRequestBuilder addPageRequest(UUID bookId) {
		return multipart("/api/books/{bookId}/pages", bookId)
				.file(jsonPart("metadata", "{\"pageNumber\":3}"))
				.file(imagePart("image", "third.png"));
	}

	private MockMultipartHttpServletRequestBuilder replaceImageRequest(UUID bookId, UUID pageId) {
		MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders
				.multipart("/api/books/{bookId}/pages/{pageId}/image", bookId, pageId);
		request.with(servletRequest -> {
			servletRequest.setMethod("PUT");
			return servletRequest;
		});
		return request.file(imagePart("image", "replacement.png"));
	}

	private org.springframework.mock.web.MockMultipartFile jsonPart(String name, String json) {
		return new org.springframework.mock.web.MockMultipartFile(
				name,
				"",
				MediaType.APPLICATION_JSON_VALUE,
				json.getBytes(StandardCharsets.UTF_8)
		);
	}

	private org.springframework.mock.web.MockMultipartFile imagePart(String name, String fileName) {
		return new org.springframework.mock.web.MockMultipartFile(
				name,
				fileName,
				MediaType.IMAGE_PNG_VALUE,
				new byte[]{1, 2, 3}
		);
	}
}
