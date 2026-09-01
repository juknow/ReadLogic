ALTER TABLE book_pages DROP CONSTRAINT ck_book_pages_ocr_status;

ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_ocr_status
        CHECK (ocr_status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'));

ALTER TABLE book_pages ADD COLUMN ocr_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_pages ADD COLUMN ocr_attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book_pages ADD COLUMN ocr_confidence NUMERIC(5, 4);
ALTER TABLE book_pages ADD COLUMN ocr_engine VARCHAR(50);
ALTER TABLE book_pages ADD COLUMN ocr_model VARCHAR(100);
ALTER TABLE book_pages ADD COLUMN ocr_last_error_code VARCHAR(100);
ALTER TABLE book_pages ADD COLUMN ocr_last_error_message TEXT;
ALTER TABLE book_pages ADD COLUMN ocr_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE book_pages ADD COLUMN ocr_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE book_pages ADD COLUMN ocr_completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE book_pages ADD COLUMN ocr_next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE book_pages ADD COLUMN text_source VARCHAR(20) NOT NULL DEFAULT 'NONE';

ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_text_source
        CHECK (text_source IN ('NONE', 'OCR', 'MANUAL'));
ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_ocr_revision CHECK (ocr_revision >= 0);
ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_ocr_attempt_count CHECK (ocr_attempt_count >= 0);
ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_ocr_confidence
        CHECK (ocr_confidence IS NULL OR (ocr_confidence >= 0 AND ocr_confidence <= 1));

UPDATE book_pages
SET text_source = 'MANUAL',
    ocr_completed_at = updated_at
WHERE ocr_status = 'READY';

UPDATE book_pages
SET ocr_requested_at = created_at,
    ocr_next_attempt_at = CURRENT_TIMESTAMP
WHERE ocr_status = 'PENDING';

CREATE INDEX idx_book_pages_ocr_queue
    ON book_pages (ocr_status, ocr_next_attempt_at);
