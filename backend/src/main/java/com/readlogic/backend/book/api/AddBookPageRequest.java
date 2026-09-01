package com.readlogic.backend.book.api;

import jakarta.validation.constraints.Positive;

public record AddBookPageRequest(@Positive int pageNumber) {
}

