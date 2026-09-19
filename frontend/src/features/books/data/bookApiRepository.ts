import { ApiError, requestApi, resolveApiUrl } from '@/shared/api/apiClient'
import type {
  Book,
  BookPage,
  BookPageOcrStatus,
  BookPageTextSource,
  BookSummary,
  CreateBookInput,
  OcrDocument,
  OcrLanguage,
} from '@/features/books/model/book'

type BookPageResponse = {
  createdAt: string
  extractedText: string
  fileName: string
  id: string
  imageUrl: string
  mimeType: string
  ocrCompletedAt: string | null
  ocrConfidence: number | null
  ocrEngine: string | null
  ocrErrorCode: string | null
  ocrErrorMessage: string | null
  ocrLanguage?: OcrLanguage | null
  ocrModel: string | null
  ocrDocument?: OcrDocument | null
  ocrRequestedAt: string | null
  ocrStatus: BookPageOcrStatus
  pageNumber: number
  textSource: BookPageTextSource
  updatedAt: string
}

type BookResponse = {
  author: string
  createdAt: string
  defaultOcrLanguage?: OcrLanguage
  id: string
  pages: BookPageResponse[]
  title: string
  updatedAt: string
}

type BookSummaryResponse = {
  author: string
  coverPage: BookPageResponse | null
  createdAt: string
  firstPageNumber: number | null
  id: string
  lastPageNumber: number | null
  pageCount: number
  title: string
  updatedAt: string
}

type ReplaceBookPageRequest = {
  id: string | null
  imageIndex: number | null
  pageNumber: number
}

export async function getBookSummaries() {
  const books = await requestApi<BookSummaryResponse[]>('/api/books')
  return books.map(mapBookSummary)
}

export async function getBookById(bookId: string) {
  try {
    return mapBook(await requestApi<BookResponse>(`/api/books/${bookId}`))
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

export async function createBookOnServer(input: CreateBookInput) {
  const metadata = {
    author: input.author.trim(),
    defaultOcrLanguage: input.defaultOcrLanguage,
    pages: input.pages.map(({ pageNumber }) => ({ pageNumber })),
    title: input.title.trim(),
  }
  const formData = new FormData()
  formData.append(
    'metadata',
    new Blob([JSON.stringify(metadata)], { type: 'application/json' }),
  )
  input.pages.forEach(({ file }) => formData.append('images', file))

  return mapBook(
    await requestApi<BookResponse>('/api/books', {
      body: formData,
      method: 'POST',
    }),
  )
}

export async function replaceBookOnServer(book: Book) {
  const images: File[] = []
  const pages: ReplaceBookPageRequest[] = book.pages.map((page) => {
    const imageIndex = page.pendingImage ? images.push(page.pendingImage) - 1 : null
    if (page.isNew && imageIndex === null) {
      throw new ApiError('새 페이지의 이미지가 없습니다.', {
        code: 'MISSING_PAGE_IMAGE',
      })
    }
    return {
      id: page.isNew ? null : page.id,
      imageIndex,
      pageNumber: page.pageNumber,
    }
  })
  const metadata = {
    author: book.author.trim(),
    defaultOcrLanguage: book.defaultOcrLanguage,
    pages,
    title: book.title.trim(),
  }
  const formData = new FormData()
  formData.append(
    'metadata',
    new Blob([JSON.stringify(metadata)], { type: 'application/json' }),
  )
  images.forEach((image) => formData.append('images', image))

  return mapBook(
    await requestApi<BookResponse>(`/api/books/${book.id}`, {
      body: formData,
      method: 'PUT',
    }),
  )
}

export async function retryPageOcr(
  bookId: string,
  pageId: string,
  language?: OcrLanguage | null,
) {
  const options: RequestInit = { method: 'POST' }
  if (language !== undefined) {
    options.body = JSON.stringify({ language })
    options.headers = { 'Content-Type': 'application/json' }
  }
  return mapBookPage(
    await requestApi<BookPageResponse>(
      `/api/books/${bookId}/pages/${pageId}/ocr`,
      options,
    ),
  )
}

export async function savePageExtractedText(
  bookId: string,
  pageId: string,
  extractedText: string,
) {
  return mapBookPage(
    await requestApi<BookPageResponse>(
      `/api/books/${bookId}/pages/${pageId}`,
      {
        body: JSON.stringify({ extractedText }),
        headers: { 'Content-Type': 'application/json' },
        method: 'PATCH',
      },
    ),
  )
}

function mapBook(book: BookResponse): Book {
  return {
    ...book,
    defaultOcrLanguage: book.defaultOcrLanguage ?? 'ko',
    pages: book.pages.map(mapBookPage),
  }
}

function mapBookSummary(book: BookSummaryResponse): BookSummary {
  return {
    ...book,
    coverPage: book.coverPage ? mapBookPage(book.coverPage) : null,
  }
}

function mapBookPage(page: BookPageResponse): BookPage {
  return {
    ...page,
    imageUrl: resolveApiUrl(page.imageUrl),
    ocrDocument: page.ocrDocument ?? null,
    ocrLanguage: page.ocrLanguage ?? null,
  }
}
