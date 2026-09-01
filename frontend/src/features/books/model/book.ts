export type BookPageOcrStatus = 'failed' | 'pending' | 'processing' | 'ready'
export type BookPageTextSource = 'manual' | 'none' | 'ocr'

export type BookPage = {
  createdAt: string
  extractedText: string
  fileName: string
  id: string
  imageUrl: string
  isNew?: boolean
  mimeType: string
  ocrCompletedAt: string | null
  ocrConfidence: number | null
  ocrEngine: string | null
  ocrErrorCode: string | null
  ocrErrorMessage: string | null
  ocrModel: string | null
  ocrRequestedAt: string | null
  ocrStatus: BookPageOcrStatus
  pageNumber: number
  pendingImage?: File
  textSource: BookPageTextSource
  updatedAt: string
}

export type Book = {
  author: string
  createdAt: string
  id: string
  pages: BookPage[]
  title: string
  updatedAt: string
}

export type BookSummary = {
  author: string
  coverPage: BookPage | null
  createdAt: string
  firstPageNumber: number | null
  id: string
  lastPageNumber: number | null
  pageCount: number
  title: string
  updatedAt: string
}

export type NewBookPageInput = {
  file: File
  pageNumber: number
}

export type CreateBookInput = {
  author: string
  pages: NewBookPageInput[]
  title: string
}

export function createBookPage(file: File, pageNumber: number): BookPage {
  const timestamp = new Date().toISOString()

  return {
    createdAt: timestamp,
    extractedText: '',
    fileName: file.name,
    id: crypto.randomUUID(),
    imageUrl: '',
    isNew: true,
    mimeType: file.type,
    ocrCompletedAt: null,
    ocrConfidence: null,
    ocrEngine: null,
    ocrErrorCode: null,
    ocrErrorMessage: null,
    ocrModel: null,
    ocrRequestedAt: timestamp,
    ocrStatus: 'pending',
    pageNumber,
    pendingImage: file,
    textSource: 'none',
    updatedAt: timestamp,
  }
}
