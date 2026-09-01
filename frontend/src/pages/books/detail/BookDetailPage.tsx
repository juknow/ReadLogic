import {
  useEffect,
  useState,
  type ChangeEvent,
  type FormEvent,
} from 'react'
import { Link, useParams } from 'react-router-dom'

import { appPaths } from '@/app/router/paths'
import {
  getBookById,
  replaceBookOnServer,
  retryPageOcr,
  savePageExtractedText,
} from '@/features/books/data/bookApiRepository'
import { useObjectUrl } from '@/features/books/hooks/useObjectUrl'
import {
  createBookPage,
  type Book,
  type BookPage,
} from '@/features/books/model/book'
import {
  ACCEPTED_BOOK_IMAGE_INPUT,
  getBookImageValidationError,
} from '@/features/books/model/bookImage'
import { ApiError } from '@/shared/api/apiClient'

import styles from './BookDetailPage.module.css'

const dateFormatter = new Intl.DateTimeFormat('ko-KR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

function getOcrStatusLabel(page: BookPage) {
  if (page.ocrStatus === 'ready') return '텍스트 인식 완료'
  if (page.ocrStatus === 'failed') return '다시 인식 필요'
  if (page.ocrStatus === 'processing') return '텍스트 인식 중'
  return '텍스트 인식 대기'
}

function getActionError(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

type PageCardProps = {
  isEditing: boolean
  onPageNumberChange: (pageId: string, pageNumber: number) => void
  onReplace: (pageId: string, file: File) => void
  onRetryOcr: (pageId: string) => Promise<void>
  onSaveText: (pageId: string, text: string) => Promise<void>
  page: BookPage
}

function PageCard({
  isEditing,
  onPageNumberChange,
  onReplace,
  onRetryOcr,
  onSaveText,
  page,
}: PageCardProps) {
  const pendingImageUrl = useObjectUrl(page.pendingImage)
  const imageUrl = pendingImageUrl || page.imageUrl
  const [textDraft, setTextDraft] = useState(page.extractedText)
  const [isTextEditing, setIsTextEditing] = useState(false)
  const [isOcrActionPending, setIsOcrActionPending] = useState(false)
  const [ocrActionError, setOcrActionError] = useState('')
  const [ocrActionMessage, setOcrActionMessage] = useState('')

  useEffect(() => {
    if (!isTextEditing) setTextDraft(page.extractedText)
  }, [isTextEditing, page.extractedText])

  function handleReplace(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (file) onReplace(page.id, file)
    event.target.value = ''
  }

  async function handleTextSave() {
    setIsOcrActionPending(true)
    setOcrActionError('')
    setOcrActionMessage('')
    try {
      await onSaveText(page.id, textDraft)
      setIsTextEditing(false)
      setOcrActionMessage('교정한 본문을 저장했습니다.')
    } catch (error) {
      setOcrActionError(getActionError(error, '추출 본문을 저장하지 못했습니다.'))
    } finally {
      setIsOcrActionPending(false)
    }
  }

  async function handleOcrRetry() {
    setIsOcrActionPending(true)
    setOcrActionError('')
    setOcrActionMessage('')
    try {
      await onRetryOcr(page.id)
      setOcrActionMessage('텍스트 재인식을 요청했습니다.')
    } catch (error) {
      setOcrActionError(getActionError(error, '텍스트 재인식을 요청하지 못했습니다.'))
    } finally {
      setIsOcrActionPending(false)
    }
  }

  return (
    <li className={styles.pageCard}>
      <div className={styles.pageImage}>
        {imageUrl && <img alt={`${page.pageNumber}쪽`} src={imageUrl} />}
        <span>{page.pageNumber}쪽</span>
      </div>
      <div className={styles.pageCardBody}>
        {isEditing ? (
          <label className={styles.pageNumberField}>
            <span>페이지 번호</span>
            <div>
              <input
                inputMode="numeric"
                min="1"
                onChange={(event) =>
                  onPageNumberChange(page.id, Number(event.target.value))
                }
                required
                type="number"
                value={page.pageNumber || ''}
              />
              <span>쪽</span>
            </div>
          </label>
        ) : (
          <strong className={styles.pageTitle}>{page.pageNumber}쪽</strong>
        )}
        <p className={styles.fileName}>{page.fileName}</p>
        <div className={styles.ocrSummary}>
          <p className={styles.ocrStatus} data-status={page.ocrStatus}>
            {(page.ocrStatus === 'pending' || page.ocrStatus === 'processing') && (
              <span aria-hidden="true" className={styles.ocrSpinner} />
            )}
            {getOcrStatusLabel(page)}
          </p>
          {page.ocrConfidence !== null && (
            <span>평균 신뢰도 {Math.round(page.ocrConfidence * 100)}%</span>
          )}
        </div>
        {!isEditing && (page.ocrStatus === 'ready' || page.ocrStatus === 'failed') && (
          <section className={styles.ocrPanel} aria-label={`${page.pageNumber}쪽 인식 본문`}>
            {(page.ocrStatus === 'ready' || page.ocrStatus === 'failed') &&
              (isTextEditing ? (
                <>
                  <label>
                    <span>추출 본문 교정</span>
                    <textarea
                      onChange={(event) => setTextDraft(event.target.value)}
                      rows={7}
                      value={textDraft}
                    />
                  </label>
                  <div className={styles.ocrActions}>
                    <button
                      disabled={isOcrActionPending}
                      onClick={() => {
                        setTextDraft(page.extractedText)
                        setIsTextEditing(false)
                        setOcrActionError('')
                      }}
                      type="button"
                    >
                      취소
                    </button>
                    <button
                      disabled={isOcrActionPending}
                      onClick={() => void handleTextSave()}
                      type="button"
                    >
                      {isOcrActionPending ? '저장 중…' : '본문 저장'}
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <p className={styles.extractedText}>
                    {page.extractedText || '인식된 텍스트가 없습니다.'}
                  </p>
                  <button
                    className={styles.textEditButton}
                    onClick={() => {
                      setIsTextEditing(true)
                      setOcrActionError('')
                      setOcrActionMessage('')
                    }}
                    type="button"
                  >
                    본문 직접 수정
                  </button>
                </>
              ))}
            {page.ocrStatus === 'failed' && (
              <div className={styles.ocrFailure}>
                <p>{page.ocrErrorMessage || '텍스트 인식에 실패했습니다.'}</p>
                {page.ocrErrorCode && <code>{page.ocrErrorCode}</code>}
                <button
                  disabled={isOcrActionPending}
                  onClick={() => void handleOcrRetry()}
                  type="button"
                >
                  {isOcrActionPending ? '요청 중…' : '다시 인식'}
                </button>
              </div>
            )}
            {page.ocrStatus === 'ready' && page.ocrModel && (
              <p className={styles.ocrModel}>
                {page.ocrEngine} · {page.ocrModel}
              </p>
            )}
            {ocrActionError && <p className={styles.ocrActionError} role="alert">{ocrActionError}</p>}
            {ocrActionMessage && <p className={styles.ocrActionMessage} role="status">{ocrActionMessage}</p>}
          </section>
        )}
        {isEditing && (
          <label className={styles.replaceAction}>
            <input
              accept={ACCEPTED_BOOK_IMAGE_INPUT}
              onChange={handleReplace}
              type="file"
            />
            이미지 교체
          </label>
        )}
      </div>
    </li>
  )
}

export function BookDetailPage() {
  const { bookId = '' } = useParams()
  const [book, setBook] = useState<Book | null>()
  const [draftBook, setDraftBook] = useState<Book | null>(null)
  const [isEditing, setIsEditing] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [saveError, setSaveError] = useState('')
  const [statusMessage, setStatusMessage] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const [newPageNumber, setNewPageNumber] = useState('')
  const [newPageFile, setNewPageFile] = useState<File | null>(null)
  const [addPageMessage, setAddPageMessage] = useState('')
  const [isTabVisible, setIsTabVisible] = useState(
    () => typeof document === 'undefined' || document.visibilityState === 'visible',
  )
  const [ocrRefreshError, setOcrRefreshError] = useState('')

  useEffect(() => {
    let isActive = true

    void getBookById(bookId)
      .then((storedBook) => {
        if (!isActive) return
        setBook(storedBook)
        setDraftBook(storedBook)
      })
      .catch((error) => {
        if (isActive) {
          setLoadError(
            error instanceof ApiError
              ? error.message
              : '책을 불러오지 못했습니다. 다시 시도해 주세요.',
          )
        }
      })

    return () => {
      isActive = false
    }
  }, [bookId])

  useEffect(() => {
    function handleVisibilityChange() {
      setIsTabVisible(document.visibilityState === 'visible')
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange)
  }, [])

  const hasActiveOcr =
    book?.pages.some(
      ({ ocrStatus }) => ocrStatus === 'pending' || ocrStatus === 'processing',
    ) ?? false

  useEffect(() => {
    if (!book || !hasActiveOcr || isEditing || !isTabVisible) return

    let isActive = true
    let isRequesting = false
    const intervalId = window.setInterval(() => {
      if (isRequesting) return
      isRequesting = true
      void getBookById(bookId)
        .then((refreshedBook) => {
          if (!isActive || !refreshedBook) return
          setBook(refreshedBook)
          setDraftBook(refreshedBook)
          setOcrRefreshError('')
        })
        .catch(() => {
          if (isActive) {
            setOcrRefreshError('OCR 진행 상태를 갱신하지 못했습니다. 자동으로 다시 시도합니다.')
          }
        })
        .finally(() => {
          isRequesting = false
        })
    }, 2_000)

    return () => {
      isActive = false
      window.clearInterval(intervalId)
    }
  }, [book, bookId, hasActiveOcr, isEditing, isTabVisible])

  const visibleBook = draftBook ?? book
  const pageNumbers = visibleBook?.pages.map(({ pageNumber }) => pageNumber) ?? []
  const hasInvalidPageNumber = pageNumbers.some((pageNumber) => pageNumber < 1)
  const hasDuplicatePageNumber =
    new Set(pageNumbers).size !== pageNumbers.length
  const canSave =
    Boolean(visibleBook?.title.trim()) &&
    pageNumbers.length > 0 &&
    !hasInvalidPageNumber &&
    !hasDuplicatePageNumber

  function startEditing() {
    if (!book) return
    setDraftBook(book)
    setIsEditing(true)
    setSaveError('')
    setStatusMessage('')
  }

  function cancelEditing() {
    setDraftBook(book ?? null)
    setIsEditing(false)
    setNewPageNumber('')
    setNewPageFile(null)
    setAddPageMessage('')
    setSaveError('')
  }

  function updateBookInfo(field: 'author' | 'title', value: string) {
    setDraftBook((currentBook) =>
      currentBook ? { ...currentBook, [field]: value } : currentBook,
    )
  }

  function updatePageNumber(pageId: string, pageNumber: number) {
    setDraftBook((currentBook) =>
      currentBook
        ? {
            ...currentBook,
            pages: currentBook.pages.map((page) =>
              page.id === pageId ? { ...page, pageNumber } : page,
            ),
          }
        : currentBook,
    )
  }

  function replacePageImage(pageId: string, file: File) {
    const validationError = getBookImageValidationError(file)
    if (validationError) {
      setSaveError(validationError)
      return
    }

    const timestamp = new Date().toISOString()
    setSaveError('')

    setDraftBook((currentBook) =>
      currentBook
        ? {
            ...currentBook,
            pages: currentBook.pages.map((page) =>
              page.id === pageId
                ? {
                    ...page,
                    extractedText: '',
                    fileName: file.name,
                    mimeType: file.type,
                    ocrCompletedAt: null,
                    ocrConfidence: null,
                    ocrEngine: null,
                    ocrErrorCode: null,
                    ocrErrorMessage: null,
                    ocrModel: null,
                    ocrRequestedAt: timestamp,
                    ocrStatus: 'pending',
                    pendingImage: file,
                    textSource: 'none',
                    updatedAt: timestamp,
                  }
                : page,
            ),
          }
        : currentBook,
    )
  }

  function applyServerPage(savedPage: BookPage) {
    const replacePage = (currentBook: Book | null | undefined) =>
      currentBook
        ? {
            ...currentBook,
            pages: currentBook.pages.map((page) =>
              page.id === savedPage.id ? savedPage : page,
            ),
          }
        : currentBook

    setBook(replacePage)
    setDraftBook((currentBook) => replacePage(currentBook) ?? null)
  }

  async function handleRetryOcr(pageId: string) {
    applyServerPage(await retryPageOcr(bookId, pageId))
  }

  async function handleSaveText(pageId: string, text: string) {
    applyServerPage(await savePageExtractedText(bookId, pageId, text))
  }

  function addPage() {
    if (!draftBook) return

    const parsedPageNumber = Number.parseInt(newPageNumber, 10)
    if (!Number.isInteger(parsedPageNumber) || parsedPageNumber < 1) {
      setAddPageMessage('추가할 페이지 번호를 입력해 주세요.')
      return
    }
    if (draftBook.pages.some(({ pageNumber }) => pageNumber === parsedPageNumber)) {
      setAddPageMessage('이미 등록된 페이지 번호입니다.')
      return
    }
    if (!newPageFile) {
      setAddPageMessage('추가할 페이지 이미지를 선택해 주세요.')
      return
    }
    const validationError = getBookImageValidationError(newPageFile)
    if (validationError) {
      setAddPageMessage(validationError)
      return
    }

    setDraftBook({
      ...draftBook,
      pages: [...draftBook.pages, createBookPage(newPageFile, parsedPageNumber)],
    })
    setNewPageNumber('')
    setNewPageFile(null)
    setAddPageMessage(`${parsedPageNumber}쪽을 추가했습니다. 변경을 저장해 주세요.`)
  }

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draftBook || !canSave) return

    setIsSaving(true)
    setSaveError('')

    try {
      const savedBook = await replaceBookOnServer({
        ...draftBook,
        author: draftBook.author.trim(),
        title: draftBook.title.trim(),
      })
      setBook(savedBook)
      setDraftBook(savedBook)
      setIsEditing(false)
      setAddPageMessage('')
      setStatusMessage('책과 페이지 변경사항을 저장했습니다.')
    } catch (error) {
      const reason =
        error instanceof ApiError
          ? error.message
          : '변경사항을 저장하지 못했습니다.'
      setSaveError(`${reason} 초안은 유지되며 서버 데이터는 변경되지 않았습니다.`)
    } finally {
      setIsSaving(false)
    }
  }

  if (loadError) {
    return (
      <section className={styles.routeState}>
        <p role="alert">{loadError}</p>
        <Link to={appPaths.books}>내 책으로 돌아가기</Link>
      </section>
    )
  }

  if (book === undefined) {
    return (
      <p className={styles.loadingState} role="status">
        책을 불러오는 중입니다.
      </p>
    )
  }

  if (book === null || !visibleBook) {
    return (
      <section className={styles.routeState}>
        <p className={styles.routeEyebrow}>BOOK NOT FOUND</p>
        <h1>책을 찾을 수 없습니다.</h1>
        <p>삭제되었거나 올바르지 않은 주소일 수 있어요.</p>
        <Link to={appPaths.books}>내 책으로 돌아가기</Link>
      </section>
    )
  }

  const sortedPages = [...visibleBook.pages].sort(
    (left, right) => left.pageNumber - right.pageNumber,
  )

  return (
    <div className={styles.page}>
      <div className={styles.breadcrumb} aria-label="현재 위치">
        <Link to={appPaths.home}>홈</Link>
        <span aria-hidden="true">/</span>
        <Link to={appPaths.books}>내 책</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{book.title}</span>
      </div>

      <header className={styles.pageHeader}>
        <div>
          <p className={styles.eyebrow}>BOOK ARCHIVE</p>
          <h1>{book.title}</h1>
          <p className={styles.headerMeta}>
            {book.author || '저자 미등록'}
            <span aria-hidden="true">·</span>
            {book.pages.length}페이지
            <span aria-hidden="true">·</span>
            {dateFormatter.format(new Date(book.updatedAt))} 수정
          </p>
        </div>
        <div className={styles.headerActions}>
          {isEditing ? (
            <>
              <button className={styles.secondaryButton} onClick={cancelEditing} type="button">
                취소
              </button>
              <button
                className={styles.primaryButton}
                disabled={!canSave || isSaving}
                form="book-edit-form"
                type="submit"
              >
                {isSaving ? '저장 중…' : '변경 저장'}
              </button>
            </>
          ) : (
            <button className={styles.primaryButton} onClick={startEditing} type="button">
              책 수정
            </button>
          )}
        </div>
      </header>

      {statusMessage && (
        <p className={styles.statusMessage} role="status">
          <span aria-hidden="true">✓</span>
          {statusMessage}
        </p>
      )}

      {ocrRefreshError && (
        <p className={styles.refreshWarning} role="status">
          {ocrRefreshError}
        </p>
      )}

      {isEditing && (
        <form className={styles.editForm} id="book-edit-form" onSubmit={handleSave}>
          <section className={styles.editSection} aria-labelledby="edit-book-info-title">
            <div className={styles.editSectionHeader}>
              <span aria-hidden="true">01</span>
              <div>
                <h2 id="edit-book-info-title">책 정보 수정</h2>
                <p>목록과 기록에 표시되는 정보를 변경합니다.</p>
              </div>
            </div>
            <div className={styles.bookFields}>
              <label>
                <span>책 제목</span>
                <input
                  maxLength={80}
                  onChange={(event) => updateBookInfo('title', event.target.value)}
                  required
                  value={visibleBook.title}
                />
              </label>
              <label>
                <span>저자 <small>선택</small></span>
                <input
                  maxLength={60}
                  onChange={(event) => updateBookInfo('author', event.target.value)}
                  value={visibleBook.author}
                />
              </label>
            </div>
          </section>

          <section className={styles.editSection} aria-labelledby="add-page-title">
            <div className={styles.editSectionHeader}>
              <span aria-hidden="true">02</span>
              <div>
                <h2 id="add-page-title">중간 페이지 추가</h2>
                <p>페이지 번호에 맞춰 저장하면 책 안에서 자동으로 정렬됩니다.</p>
              </div>
            </div>
            <div className={styles.addPageControls}>
              <label className={styles.newPageNumberField}>
                <span>페이지 번호</span>
                <div>
                  <input
                    inputMode="numeric"
                    min="1"
                    onChange={(event) => {
                      setNewPageNumber(event.target.value)
                      setAddPageMessage('')
                    }}
                    placeholder="예: 43"
                    type="number"
                    value={newPageNumber}
                  />
                  <span>쪽</span>
                </div>
              </label>
              <label className={styles.newPageFileField}>
                <span>페이지 이미지</span>
                <div>
                  <input
                    accept={ACCEPTED_BOOK_IMAGE_INPUT}
                    onChange={(event) => {
                      const file = event.target.files?.[0] ?? null
                      const validationError = file
                        ? getBookImageValidationError(file)
                        : null
                      setNewPageFile(validationError ? null : file)
                      setAddPageMessage(validationError ?? '')
                      if (validationError) event.target.value = ''
                    }}
                    type="file"
                  />
                  <span>{newPageFile?.name || '이미지 선택'}</span>
                </div>
              </label>
              <button className={styles.addPageButton} onClick={addPage} type="button">
                페이지 추가
              </button>
            </div>
            {addPageMessage && (
              <p className={styles.addPageMessage} role="status">
                {addPageMessage}
              </p>
            )}
          </section>

          {(hasInvalidPageNumber || hasDuplicatePageNumber) && (
            <p className={styles.validationMessage} role="alert">
              {hasDuplicatePageNumber
                ? '중복된 페이지 번호를 확인해 주세요.'
                : '모든 페이지 번호는 1 이상이어야 합니다.'}
            </p>
          )}
          {saveError && (
            <p className={styles.validationMessage} role="alert">
              {saveError}
            </p>
          )}
        </form>
      )}

      <section className={styles.pagesSection} aria-labelledby="book-pages-title">
        <div className={styles.pagesHeader}>
          <div>
            <p>REGISTERED PAGES</p>
            <h2 id="book-pages-title">수록된 페이지</h2>
          </div>
          <span>{sortedPages.length}페이지</span>
        </div>
        <ol className={styles.pageGrid}>
          {sortedPages.map((page) => (
            <PageCard
              isEditing={isEditing}
              key={page.id}
              onPageNumberChange={updatePageNumber}
              onReplace={replacePageImage}
              onRetryOcr={handleRetryOcr}
              onSaveText={handleSaveText}
              page={page}
            />
          ))}
        </ol>
      </section>
    </div>
  )
}
