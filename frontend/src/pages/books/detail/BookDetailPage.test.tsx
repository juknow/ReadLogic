// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { Book, BookPage, BookPageOcrStatus } from '@/features/books/model/book'

import { BookDetailPage } from './BookDetailPage'

const apiMocks = vi.hoisted(() => ({
  getBookById: vi.fn(),
  replaceBookOnServer: vi.fn(),
  retryPageOcr: vi.fn(),
  savePageExtractedText: vi.fn(),
}))

vi.mock('@/features/books/data/bookApiRepository', () => apiMocks)

const timestamp = '2026-09-01T01:00:00Z'

function createPage(
  id: string,
  ocrStatus: BookPageOcrStatus,
  overrides: Partial<BookPage> = {},
): BookPage {
  return {
    createdAt: timestamp,
    extractedText: ocrStatus === 'ready' ? '서버 인식 본문' : '',
    fileName: `${id}.png`,
    id,
    imageUrl: `http://localhost:8080/${id}.png`,
    mimeType: 'image/png',
    ocrCompletedAt: ocrStatus === 'ready' ? timestamp : null,
    ocrConfidence: ocrStatus === 'ready' ? 0.93 : null,
    ocrEngine: ocrStatus === 'ready' ? 'paddleocr' : null,
    ocrErrorCode: ocrStatus === 'failed' ? 'OCR_INFERENCE_FAILED' : null,
    ocrErrorMessage: ocrStatus === 'failed' ? '인식에 실패했습니다.' : null,
    ocrModel: ocrStatus === 'ready' ? 'PP-OCRv5-korean' : null,
    ocrRequestedAt: timestamp,
    ocrStatus,
    pageNumber: id === 'page-1' ? 1 : 2,
    textSource: ocrStatus === 'ready' ? 'ocr' : 'none',
    updatedAt: timestamp,
    ...overrides,
  }
}

function createBook(pages: BookPage[]): Book {
  return {
    author: '저자',
    createdAt: timestamp,
    id: 'book-1',
    pages,
    title: 'OCR 테스트 책',
    updatedAt: timestamp,
  }
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/books/book-1']}>
      <Routes>
        <Route element={<BookDetailPage />} path="/books/:bookId" />
      </Routes>
    </MemoryRouter>,
  )
}

function captureIntervals() {
  const callbacks: Array<() => void> = []
  const originalSetInterval = window.setInterval.bind(window)
  const setIntervalSpy = vi
    .spyOn(window, 'setInterval')
    .mockImplementation((handler: TimerHandler, timeout?: number, ...args: unknown[]) => {
      if (timeout === 2_000) {
        callbacks.push(handler as () => void)
        return 10_000 + callbacks.length
      }
      return originalSetInterval(handler, timeout, ...args)
    })
  const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
  return { callbacks, clearIntervalSpy, setIntervalSpy }
}

async function runInterval(callback: (() => void) | undefined) {
  await act(async () => {
    callback?.()
    await new Promise((resolve) => window.setTimeout(resolve, 0))
  })
}

beforeEach(() => {
  Object.values(apiMocks).forEach((mock) => mock.mockReset())
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: 'visible',
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: 'visible',
  })
})

describe('BookDetailPage OCR', () => {
  it('완료 응답을 받으면 상태 폴링을 중단한다', async () => {
    const pendingBook = createBook([createPage('page-1', 'pending')])
    const readyBook = createBook([createPage('page-1', 'ready')])
    apiMocks.getBookById.mockResolvedValueOnce(pendingBook).mockResolvedValueOnce(readyBook)
    const intervals = captureIntervals()

    renderDetail()
    await screen.findByText('텍스트 인식 대기')
    await runInterval(intervals.callbacks[0])

    expect(await screen.findByText('텍스트 인식 완료')).toBeTruthy()
    expect(intervals.callbacks).toHaveLength(1)
    expect(intervals.clearIntervalSpy).toHaveBeenCalled()
  })

  it('탭이 숨겨지거나 책 구조를 편집하면 폴링을 중단한다', async () => {
    apiMocks.getBookById.mockResolvedValue(createBook([createPage('page-1', 'processing')]))
    const intervals = captureIntervals()
    renderDetail()
    await screen.findByText('텍스트 인식 중')

    const clearCountBeforeHidden = intervals.clearIntervalSpy.mock.calls.length
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'hidden',
    })
    fireEvent(document, new Event('visibilitychange'))
    expect(intervals.clearIntervalSpy.mock.calls.length).toBeGreaterThan(clearCountBeforeHidden)

    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    })
    fireEvent(document, new Event('visibilitychange'))
    const clearCountBeforeEditing = intervals.clearIntervalSpy.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: '책 수정' }))
    expect(intervals.clearIntervalSpy.mock.calls.length).toBeGreaterThan(clearCountBeforeEditing)
  })

  it('폴링 응답이 사용자가 입력 중인 본문 초안을 덮어쓰지 않는다', async () => {
    const firstBook = createBook([
      createPage('page-1', 'ready'),
      createPage('page-2', 'pending'),
    ])
    const refreshedBook = createBook([
      createPage('page-1', 'ready', { extractedText: '서버에서 바뀐 본문' }),
      createPage('page-2', 'processing'),
    ])
    apiMocks.getBookById.mockResolvedValueOnce(firstBook).mockResolvedValueOnce(refreshedBook)
    const intervals = captureIntervals()
    renderDetail()
    await screen.findByText('서버 인식 본문')

    fireEvent.click(screen.getByRole('button', { name: '본문 직접 수정' }))
    const textarea = screen.getByRole('textbox', { name: '추출 본문 교정' })
    fireEvent.change(textarea, { target: { value: '사용자가 입력 중인 초안' } })
    await runInterval(intervals.callbacks[0])

    expect((screen.getByRole('textbox', { name: '추출 본문 교정' }) as HTMLTextAreaElement).value)
      .toBe('사용자가 입력 중인 초안')
  })

  it('실패한 페이지의 재인식을 요청하고 서버 상태를 반영한다', async () => {
    const failedPage = createPage('page-1', 'failed')
    const pendingPage = createPage('page-1', 'pending')
    apiMocks.getBookById.mockResolvedValue(createBook([failedPage]))
    apiMocks.retryPageOcr.mockResolvedValue(pendingPage)
    renderDetail()
    await screen.findByText('다시 인식 필요')

    fireEvent.click(screen.getByRole('button', { name: '다시 인식' }))

    expect(apiMocks.retryPageOcr).toHaveBeenCalledWith('book-1', 'page-1')
    expect(await screen.findByText('텍스트 인식 대기')).toBeTruthy()
  })

  it('교정 본문을 저장하고 네트워크 오류는 초안과 함께 표시한다', async () => {
    const readyPage = createPage('page-1', 'ready')
    apiMocks.getBookById.mockResolvedValue(createBook([readyPage]))
    apiMocks.savePageExtractedText.mockRejectedValueOnce(new Error('network'))
    renderDetail()
    await screen.findByText('서버 인식 본문')

    fireEvent.click(screen.getByRole('button', { name: '본문 직접 수정' }))
    fireEvent.change(screen.getByRole('textbox', { name: '추출 본문 교정' }), {
      target: { value: '교정 중인 본문' },
    })
    fireEvent.click(screen.getByRole('button', { name: '본문 저장' }))

    expect((await screen.findByRole('alert')).textContent).toContain(
      '추출 본문을 저장하지 못했습니다.',
    )
    expect((screen.getByRole('textbox', { name: '추출 본문 교정' }) as HTMLTextAreaElement).value)
      .toBe('교정 중인 본문')

    apiMocks.savePageExtractedText.mockResolvedValueOnce(
      createPage('page-1', 'ready', {
        extractedText: '교정 중인 본문',
        textSource: 'manual',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '본문 저장' }))

    expect(apiMocks.savePageExtractedText).toHaveBeenLastCalledWith(
      'book-1',
      'page-1',
      '교정 중인 본문',
    )
    expect(await screen.findByText('교정한 본문을 저장했습니다.')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: '추출 본문 교정' })).toBeNull()
  })
})
