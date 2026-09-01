export type BookPageOcrStatus = 'failed' | 'pending' | 'ready'

export type BookPage = {
  createdAt: string
  extractedText: string
  fileName: string
  id: string
  image: Blob
  mimeType: string
  ocrStatus: BookPageOcrStatus
  pageNumber: number
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
    image: file,
    mimeType: file.type,
    ocrStatus: 'pending',
    pageNumber,
    updatedAt: timestamp,
  }
}
