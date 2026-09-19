ALTER TABLE books ADD COLUMN default_ocr_language VARCHAR(10) NOT NULL DEFAULT 'KO';

ALTER TABLE books
    ADD CONSTRAINT ck_books_default_ocr_language
        CHECK (default_ocr_language IN ('AUTO', 'KO', 'EN', 'JA', 'ZH'));

ALTER TABLE book_pages ADD COLUMN ocr_language VARCHAR(10);
ALTER TABLE book_pages ADD COLUMN ocr_document JSONB;

ALTER TABLE book_pages
    ADD CONSTRAINT ck_book_pages_ocr_language
        CHECK (ocr_language IS NULL OR ocr_language IN ('AUTO', 'KO', 'EN', 'JA', 'ZH'));
