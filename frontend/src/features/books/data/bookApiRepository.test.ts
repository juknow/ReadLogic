import { afterEach, describe, expect, it, vi } from 'vitest'

import type { Book } from '@/features/books/model/book'

import {
  createBookOnServer,
  getBookSummaries,
  replaceBookOnServer,
} from './bookApiRepository'

const pageResponse = {
  createdAt: '2026-09-01T01:00:00Z',
  extractedText: '',
  fileName: 'page.png',
  id: 'page-1',
  imageUrl: '/api/books/book-1/pages/page-1/image',
  mimeType: 'image/png',
  ocrCompletedAt: null,
  ocrConfidence: null,
  ocrEngine: null,
  ocrErrorCode: null,
  ocrErrorMessage: null,
  ocrModel: null,
  ocrRequestedAt: '2026-09-01T01:00:00Z',
  ocrStatus: 'pending',
  pageNumber: 10,
  textSource: 'none',
  updatedAt: '2026-09-01T01:00:00Z',
} as const

const bookResponse = {
  author: '저자',
  createdAt: '2026-09-01T01:00:00Z',
  id: 'book-1',
  pages: [pageResponse],
  title: '책 제목',
  updatedAt: '2026-09-01T01:00:00Z',
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return Response.json(body, { status })
}

function getRequestFormData(fetchMock: ReturnType<typeof vi.fn>) {
  const init = fetchMock.mock.calls[0]?.[1] as RequestInit
  return init.body as FormData
}

async function readMetadata(formData: FormData) {
  const metadata = formData.get('metadata')
  expect(metadata).toBeInstanceOf(Blob)
  return JSON.parse(await (metadata as Blob).text()) as unknown
}

describe('bookApiRepository', () => {
  it('목록 응답과 상대 대표 이미지 URL을 화면 모델로 변환한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            ...bookResponse,
            coverPage: pageResponse,
            firstPageNumber: 10,
            lastPageNumber: 10,
            pageCount: 1,
            pages: undefined,
          },
        ]),
      ),
    )

    const books = await getBookSummaries()

    expect(books[0]).toMatchObject({
      firstPageNumber: 10,
      lastPageNumber: 10,
      pageCount: 1,
    })
    expect(books[0].coverPage?.imageUrl).toBe(
      'http://localhost:8080/api/books/book-1/pages/page-1/image',
    )
  })

  it('책 등록 metadata와 이미지를 페이지 순서대로 전송한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(bookResponse, 201))
    vi.stubGlobal('fetch', fetchMock)
    const firstImage = new File(['first'], 'first.png', { type: 'image/png' })
    const secondImage = new File(['second'], 'second.webp', {
      type: 'image/webp',
    })

    await createBookOnServer({
      author: ' 저자 ',
      pages: [
        { file: firstImage, pageNumber: 10 },
        { file: secondImage, pageNumber: 11 },
      ],
      title: ' 책 제목 ',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/books',
      expect.objectContaining({ method: 'POST' }),
    )
    const formData = getRequestFormData(fetchMock)
    await expect(readMetadata(formData)).resolves.toEqual({
      author: '저자',
      pages: [{ pageNumber: 10 }, { pageNumber: 11 }],
      title: '책 제목',
    })
    expect(formData.getAll('images')).toEqual([firstImage, secondImage])
  })

  it('일괄 수정 이미지와 imageIndex를 같은 순서로 만든다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(bookResponse))
    vi.stubGlobal('fetch', fetchMock)
    const replacement = new File(['replacement'], 'replacement.png', {
      type: 'image/png',
    })
    const addition = new File(['addition'], 'addition.webp', {
      type: 'image/webp',
    })
    const book: Book = {
      ...bookResponse,
      pages: [
        { ...pageResponse, pageNumber: 11, pendingImage: replacement },
        {
          ...pageResponse,
          fileName: addition.name,
          id: 'local-page',
          imageUrl: '',
          isNew: true,
          mimeType: addition.type,
          pageNumber: 10,
          pendingImage: addition,
        },
      ],
    }

    await replaceBookOnServer(book)

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/books/book-1',
      expect.objectContaining({ method: 'PUT' }),
    )
    const formData = getRequestFormData(fetchMock)
    await expect(readMetadata(formData)).resolves.toEqual({
      author: '저자',
      pages: [
        { id: 'page-1', imageIndex: 0, pageNumber: 11 },
        { id: null, imageIndex: 1, pageNumber: 10 },
      ],
      title: '책 제목',
    })
    expect(formData.getAll('images')).toEqual([replacement, addition])
  })
})
