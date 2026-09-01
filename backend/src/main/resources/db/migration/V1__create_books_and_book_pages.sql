CREATE TABLE books (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE book_pages (
    id UUID PRIMARY KEY,
    book_id UUID NOT NULL,
    page_number INTEGER NOT NULL,
    original_file_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    extracted_text TEXT NOT NULL DEFAULT '',
    ocr_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_book_pages_book
        FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT uk_book_pages_book_page_number
        UNIQUE (book_id, page_number),
    CONSTRAINT ck_book_pages_positive_page_number
        CHECK (page_number > 0),
    CONSTRAINT ck_book_pages_ocr_status
        CHECK (ocr_status IN ('PENDING', 'READY', 'FAILED'))
);

CREATE INDEX idx_book_pages_book_id ON book_pages (book_id);

