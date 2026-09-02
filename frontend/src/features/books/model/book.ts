export type BookPageOcrStatus = 'failed' | 'pending' | 'processing' | 'ready'
export type BookPageTextSource = 'manual' | 'none' | 'ocr'
export type OcrLanguage = 'auto' | 'en' | 'ja' | 'ko' | 'zh'
export type DetectedOcrLanguage = 'en' | 'ja' | 'ko' | 'mixed' | 'und' | 'zh'
export type OcrParagraphType = 'body' | 'title' | 'unknown'

export const OCR_LANGUAGE_OPTIONS = [
  { label: '한국어', value: 'ko' },
  { label: '영어', value: 'en' },
  { label: '일본어', value: 'ja' },
  { label: '중국어', value: 'zh' },
  { label: '자동 감지', value: 'auto' },
] as const satisfies ReadonlyArray<{ label: string; value: OcrLanguage }>

export function getOcrLanguageLabel(language: DetectedOcrLanguage | OcrLanguage) {
  if (language === 'mixed') return '혼합 언어'
  if (language === 'und') return '판정 불가'
  return OCR_LANGUAGE_OPTIONS.find(({ value }) => value === language)?.label ?? language
}

export type OcrWarning = {
  code: string
  message: string
}

export type OcrLine = {
  bbox: [number, number, number, number]
  confidence: number
  detectionConfidence: number | null
  id: number
  language: DetectedOcrLanguage
  model: string
  order: number
  polygon: [number, number][]
  text: string
}

export type OcrParagraph = {
  bbox: [number, number, number, number]
  confidence: number
  id: number
  lines: OcrLine[]
  order: number
  text: string
  type: OcrParagraphType
}

export type OcrDocument = {
  coordinateSpace: 'corrected_image'
  correction: {
    exifApplied: boolean
    fallbackUsed: boolean
    orientationApplied: boolean
    rotationDegrees: number
    unwarpingApplied: boolean
  }
  detectedLanguage: DetectedOcrLanguage
  image: {
    height: number
    width: number
  }
  models: {
    detector: string
    orientation: string
    recognizers: string[]
    textLineOrientation: string
    unwarping: string
  }
  paragraphs: OcrParagraph[]
  requestedLanguage: OcrLanguage
  schemaVersion: 1
  warnings: OcrWarning[]
}

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
  ocrLanguage: OcrLanguage | null
  ocrModel: string | null
  ocrDocument: OcrDocument | null
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
  defaultOcrLanguage: OcrLanguage
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
  defaultOcrLanguage: OcrLanguage
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
    ocrLanguage: null,
    ocrModel: null,
    ocrDocument: null,
    ocrRequestedAt: timestamp,
    ocrStatus: 'pending',
    pageNumber,
    pendingImage: file,
    textSource: 'none',
    updatedAt: timestamp,
  }
}
