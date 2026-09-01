export type BookPageOcrStatus = 'failed' | 'pending' | 'ready'

export type BookPage = {
  createdAt: string
  extractedText: string
  fileName: string
  id: string
  imageUrl: string
  isNew?: boolean
  mimeType: string
  ocrStatus: BookPageOcrStatus
  pageNumber: number
  pendingImage?: File
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
    ocrStatus: 'pending',
    pageNumber,
    pendingImage: file,
    updatedAt: timestamp,
  }
}
