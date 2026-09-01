package com.readlogic.backend.ocr.infrastructure;

import com.readlogic.backend.ocr.application.OcrClient;
import com.readlogic.backend.ocr.application.OcrClientException;
import com.readlogic.backend.ocr.application.OcrImage;
import com.readlogic.backend.ocr.application.OcrResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

@Component
public class HttpOcrClient implements OcrClient {

	private static final String CONTRACT_ERROR = "OCR_INVALID_RESPONSE";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public HttpOcrClient(OcrProperties properties, ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory)
				.build();
		this.objectMapper = objectMapper;
	}

	@Override
	public OcrResult recognize(OcrImage image, UUID requestId) {
		try {
			OcrResponse response = restClient.post()
					.uri("/internal/v1/ocr")
					.header("X-Ocr-Request-Id", requestId.toString())
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(toMultipart(image))
					.retrieve()
					.onStatus(status -> status.isError(), (request, clientResponse) -> {
						throw toException(clientResponse);
					})
					.body(OcrResponse.class);
			if (response == null || response.text() == null || response.engine() == null || response.model() == null) {
				throw new OcrClientException(CONTRACT_ERROR, "OCR 서비스 응답 형식이 올바르지 않습니다.", true);
			}
			return new OcrResult(response.text(), response.confidence(), response.engine(), response.model());
		} catch (OcrClientException exception) {
			throw exception;
		} catch (RestClientException exception) {
			throw new OcrClientException(
					"OCR_SERVICE_UNAVAILABLE",
					"OCR 서비스에 연결하거나 응답을 읽지 못했습니다.",
					true,
					exception
			);
		}
	}

	private MultiValueMap<String, Object> toMultipart(OcrImage image) {
		ByteArrayResource resource = new ByteArrayResource(image.content()) {
			@Override
			public String getFilename() {
				return image.fileName();
			}
		};
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(image.contentType()));
		MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
		multipart.add("image", new HttpEntity<>(resource, headers));
		return multipart;
	}

	private OcrClientException toException(ClientHttpResponse response) throws IOException {
		int statusCode = response.getStatusCode().value();
		OcrErrorResponse error = readError(response);
		String code = error == null || error.code() == null
				? "OCR_HTTP_%d".formatted(statusCode)
				: error.code();
		String message = error == null || error.message() == null
				? "OCR 서비스가 오류를 반환했습니다."
				: error.message();
		boolean retryable = statusCode == 429 || statusCode >= 500;
		return new OcrClientException(code, message, retryable);
	}

	private OcrErrorResponse readError(ClientHttpResponse response) {
		try {
			return objectMapper.readValue(response.getBody(), OcrErrorResponse.class);
		} catch (Exception ignored) {
			return null;
		}
	}

	private record OcrResponse(
			String text,
			BigDecimal confidence,
			String engine,
			String model,
			long processingTimeMs
	) {
	}

	private record OcrErrorResponse(String code, String message) {
	}
}
