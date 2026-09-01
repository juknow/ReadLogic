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
				.andExpect(jsonPath("$[0].firstPageNumber").value(1))
				.andExpect(jsonPath("$[0].lastPageNumber").value(2))
				.andExpect(jsonPath("$[0].coverPage.pageNumber").value(1));

		mockMvc.perform(get("/api/books/{bookId}", savedBook.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pages.length()").value(2));
	}

	@Test
	void replacesBookMetadataWithoutChangingPages() throws Exception {
		when(imageStorage.store(any(), any(), eq("image/png"), anyLong(), any()))
				.thenReturn("object-1", "object-2");
		mockMvc.perform(createBookRequest()).andExpect(status().isCreated());
		Book book = bookRepository.findAllByOrderByCreatedAtDesc().getFirst();

		String metadata = """
				{
				  "title":"일괄 수정된 책",
				  "author":"새 저자",
				  "pages":[
				    {"id":"%s","pageNumber":1,"imageIndex":null},
				    {"id":"%s","pageNumber":2,"imageIndex":null}
				  ]
				}
				""".formatted(book.getPages().get(0).getId(), book.getPages().get(1).getId());

		mockMvc.perform(replaceBookRequest(book.getId(), metadata))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("일괄 수정된 책"))
				.andExpect(jsonPath("$.author").value("새 저자"))
				.andExpect(jsonPath("$.pages.length()").value(2));
	}

	@Test
	void swapsPageNumbersAndSavesNewAndReplacementImagesTogether() throws Exception {
		when(imageStorage.store(any(), any(), eq("image/png"), anyLong(), any()))
				.thenReturn("object-1", "object-2", "object-3", "object-4");
		mockMvc.perform(createBookRequest()).andExpect(status().isCreated());
		Book book = bookRepository.findAllByOrderByCreatedAtDesc().getFirst();
		BookPage firstPage = book.getPages().get(0);
		BookPage secondPage = book.getPages().get(1);

		String metadata = """
				{
				  "title":"복합 수정된 책",
				  "author":"저자",
				  "pages":[
				    {"id":"%s","pageNumber":2,"imageIndex":0},
				    {"id":"%s","pageNumber":1,"imageIndex":null},
				    {"id":null,"pageNumber":3,"imageIndex":1}
				  ]
				}
				""".formatted(firstPage.getId(), secondPage.getId());

		mockMvc.perform(replaceBookRequest(book.getId(), metadata)
					.file(imagePart("images", "replacement.png"))
					.file(imagePart("images", "third.png")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pages[0].id").value(secondPage.getId().toString()))
				.andExpect(jsonPath("$.pages[0].pageNumber").value(1))
				.andExpect(jsonPath("$.pages[1].id").value(firstPage.getId().toString()))
				.andExpect(jsonPath("$.pages[1].pageNumber").value(2))
				.andExpect(jsonPath("$.pages[1].fileName").value("replacement.png"))
				.andExpect(jsonPath("$.pages[2].pageNumber").value(3))
				.andExpect(jsonPath("$.pages[2].fileName").value("third.png"));
		verify(imageStorage).delete("object-1");
	}

	@Test
	void rejectsInvalidAggregateReplacementRequests() throws Exception {
		when(imageStorage.store(any(), any(), eq("image/png"), anyLong(), any()))
				.thenReturn("object-1", "object-2");
		mockMvc.perform(createBookRequest()).andExpect(status().isCreated());
		Book book = bookRepository.findAllByOrderByCreatedAtDesc().getFirst();
		BookPage firstPage = book.getPages().get(0);

		String missingPageMetadata = """
				{"title":"잘못된 수정","author":"","pages":[
				  {"id":"%s","pageNumber":1,"imageIndex":null}
				]}
				""".formatted(firstPage.getId());
		mockMvc.perform(replaceBookRequest(book.getId(), missingPageMetadata))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		String duplicateNumberMetadata = """
				{"title":"잘못된 수정","author":"","pages":[
				  {"id":"%s","pageNumber":1,"imageIndex":null},
				  {"id":"%s","pageNumber":1,"imageIndex":null}
				]}
				""".formatted(book.getPages().get(0).getId(), book.getPages().get(1).getId());
		mockMvc.perform(replaceBookRequest(book.getId(), duplicateNumberMetadata))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PAGE_NUMBER"));

		String invalidImageIndexMetadata = """
				{"title":"잘못된 수정","author":"","pages":[
				  {"id":"%s","pageNumber":1,"imageIndex":1},
				  {"id":"%s","pageNumber":2,"imageIndex":null}
				]}
				""".formatted(book.getPages().get(0).getId(), book.getPages().get(1).getId());
		mockMvc.perform(replaceBookRequest(book.getId(), invalidImageIndexMetadata)
					.file(imagePart("images", "replacement.png")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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

	private MockMultipartHttpServletRequestBuilder replaceBookRequest(UUID bookId, String metadata) {
		MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders
				.multipart("/api/books/{bookId}", bookId);
		request.with(servletRequest -> {
			servletRequest.setMethod("PUT");
			return servletRequest;
		});
		return request.file(jsonPart("metadata", metadata));
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
