package com.readlogic.backend.ocr;

import com.readlogic.backend.ocr.application.OcrClientException;
import com.readlogic.backend.ocr.application.OcrImage;
import com.readlogic.backend.ocr.infrastructure.HttpOcrClient;
import com.readlogic.backend.ocr.infrastructure.OcrProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOcrClientTests {

	private HttpServer server;
	private HttpOcrClient client;
	private final AtomicReference<String> requestId = new AtomicReference<>();
	private final AtomicReference<String> requestBody = new AtomicReference<>();

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/v1/ocr", this::handleRequest);
		server.start();
		client = new HttpOcrClient(properties(server.getAddress().getPort()), new ObjectMapper());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void sendsMultipartImageAndRequestIdAndMapsResponse() {
		UUID id = UUID.randomUUID();
		var result = client.recognize(new OcrImage(new byte[]{1, 2, 3}, "image/png", "page.png"), id);

		assertThat(requestId.get()).isEqualTo(id.toString());
		assertThat(requestBody.get()).contains("name=\"image\"").contains("filename=\"page.png\"");
		assertThat(result.text()).isEqualTo("인식한 문장");
		assertThat(result.confidence()).isEqualByComparingTo("0.9421");
		assertThat(result.engine()).isEqualTo("paddleocr");
		assertThat(result.model()).isEqualTo("PP-OCRv5-korean");
	}

	@Test
	void classifiesPermanentAndRetryableHttpErrors() {
		server.removeContext("/internal/v1/ocr");
		server.createContext("/internal/v1/ocr", exchange -> respond(
				exchange,
				415,
				"{\"code\":\"UNSUPPORTED_IMAGE_TYPE\",\"message\":\"지원하지 않는 이미지입니다.\"}"
		));

		assertThatThrownBy(() -> recognize())
				.isInstanceOfSatisfying(OcrClientException.class, exception -> {
					assertThat(exception.getCode()).isEqualTo("UNSUPPORTED_IMAGE_TYPE");
					assertThat(exception.isRetryable()).isFalse();
				});

		server.removeContext("/internal/v1/ocr");
		server.createContext("/internal/v1/ocr", exchange -> respond(
				exchange,
				503,
				"{\"code\":\"OCR_NOT_READY\",\"message\":\"모델 준비 중입니다.\"}"
		));

		assertThatThrownBy(() -> recognize())
				.isInstanceOfSatisfying(OcrClientException.class, exception -> {
					assertThat(exception.getCode()).isEqualTo("OCR_NOT_READY");
					assertThat(exception.isRetryable()).isTrue();
				});
	}

	@Test
	void treatsConnectionFailureAsRetryable() {
		server.stop(0);

		assertThatThrownBy(() -> recognize())
				.isInstanceOfSatisfying(OcrClientException.class, exception -> {
					assertThat(exception.getCode()).isEqualTo("OCR_SERVICE_UNAVAILABLE");
					assertThat(exception.isRetryable()).isTrue();
				});
	}

	private void recognize() {
		client.recognize(new OcrImage(new byte[]{1}, "image/png", "page.png"), UUID.randomUUID());
	}

	private void handleRequest(HttpExchange exchange) throws IOException {
		requestId.set(exchange.getRequestHeaders().getFirst("X-Ocr-Request-Id"));
		requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
		respond(exchange, 200, """
				{"text":"인식한 문장","confidence":0.9421,"engine":"paddleocr",\
				"model":"PP-OCRv5-korean","processingTimeMs":123}
				""");
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] response = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, response.length);
		exchange.getResponseBody().write(response);
		exchange.close();
	}

	private OcrProperties properties(int port) {
		return new OcrProperties(
				true,
				URI.create("http://127.0.0.1:" + port),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				3,
				Duration.ofSeconds(2),
				Duration.ofMinutes(5),
				1,
				5
		);
	}
}
