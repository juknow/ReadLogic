package com.readlogic.backend.common.error;

import com.readlogic.backend.storage.ImageStorageException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception) {
		return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage());
	}

	@ExceptionHandler({DuplicateResourceException.class, DataIntegrityViolationException.class})
	ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException exception) {
		return response(HttpStatus.CONFLICT, "DUPLICATE_PAGE_NUMBER", "한 책에서 페이지 번호는 중복될 수 없습니다.");
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidRequestException exception) {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
			fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		return ResponseEntity.badRequest().body(ApiErrorResponse.withFields(
				"VALIDATION_FAILED",
				"요청 값을 확인해 주세요.",
				fieldErrors
		));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getConstraintViolations().forEach(violation ->
				fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage())
		);
		return ResponseEntity.badRequest().body(ApiErrorResponse.withFields(
				"VALIDATION_FAILED",
				"요청 값을 확인해 주세요.",
				fieldErrors
		));
	}

	@ExceptionHandler({
			MissingServletRequestPartException.class,
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception exception) {
		return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "요청 형식이 올바르지 않습니다.");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
		return response(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "업로드 가능한 이미지 용량을 초과했습니다.");
	}

	@ExceptionHandler(ImageStorageException.class)
	ResponseEntity<ApiErrorResponse> handleStorage(ImageStorageException exception) {
		return response(HttpStatus.BAD_GATEWAY, "IMAGE_STORAGE_ERROR", exception.getMessage());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.");
	}

	private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message));
	}
}
