import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type FormEvent,
} from 'react'
import { Link } from 'react-router-dom'

import { appPaths } from '@/app/router/paths'

import styles from './NewBookPage.module.css'

const MAX_PAGE_IMAGE_COUNT = 20

const recognitionSteps = [
  { label: '책 정보 저장', description: '제목과 저자' },
  { label: '페이지 구분', description: '이미지 한 장씩' },
  { label: '텍스트 변환', description: 'OCR 인식' },
  { label: '원문 보관', description: '페이지별 저장' },
] as const

type BookPageImage = {
  file: File
  id: string
  pageNumber: string
  previewUrl: string
}

function formatFileSize(size: number) {
  if (size < 1024 * 1024) {
    return `${Math.max(1, Math.round(size / 1024))}KB`
  }

  return `${(size / (1024 * 1024)).toFixed(1)}MB`
}

export function NewBookPage() {
  const [bookTitle, setBookTitle] = useState('')
  const [author, setAuthor] = useState('')
  const [firstPageNumber, setFirstPageNumber] = useState('')
  const [pages, setPages] = useState<BookPageImage[]>([])
  const [isDragging, setIsDragging] = useState(false)
  const [uploadMessage, setUploadMessage] = useState('')
  const [isRegistrationReady, setIsRegistrationReady] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const previewUrlsRef = useRef(new Set<string>())

  const pageNumbers = pages.map(({ pageNumber }) => pageNumber.trim())
  const hasEmptyPageNumber = pageNumbers.some((pageNumber) => !pageNumber)
  const hasDuplicatePageNumber = new Set(pageNumbers).size !== pageNumbers.length
  const isReady =
    bookTitle.trim().length > 0 &&
    pages.length > 0 &&
    !hasEmptyPageNumber &&
    !hasDuplicatePageNumber

  useEffect(() => {
    const previewUrls = previewUrlsRef.current

    return () => {
      previewUrls.forEach((previewUrl) => URL.revokeObjectURL(previewUrl))
    }
  }, [])

  function addPageImages(fileList: FileList | null) {
    if (!fileList) return

    const selectedFiles = Array.from(fileList)
    const imageFiles = selectedFiles.filter((file) =>
      file.type.startsWith('image/'),
    )
    const remainingSlots = MAX_PAGE_IMAGE_COUNT - pages.length
    const acceptedFiles = imageFiles.slice(0, remainingSlots)
    const parsedFirstPageNumber = Number.parseInt(firstPageNumber, 10)
    const hasStartingPageNumber = Number.isInteger(parsedFirstPageNumber) && parsedFirstPageNumber > 0

    if (acceptedFiles.length > 0) {
      const nextPages = acceptedFiles.map((file, index) => {
        const previewUrl = URL.createObjectURL(file)
        previewUrlsRef.current.add(previewUrl)

        return {
          file,
          id: crypto.randomUUID(),
          pageNumber: hasStartingPageNumber
            ? String(parsedFirstPageNumber + index)
            : '',
          previewUrl,
        }
      })

      setPages((currentPages) => [...currentPages, ...nextPages])
      if (hasStartingPageNumber) {
        setFirstPageNumber(String(parsedFirstPageNumber + acceptedFiles.length))
      }
      setIsRegistrationReady(false)
    }

    if (imageFiles.length !== selectedFiles.length) {
      setUploadMessage('이미지 파일만 페이지로 등록할 수 있어요.')
    } else if (imageFiles.length > remainingSlots) {
      setUploadMessage(
        `한 번에 최대 ${MAX_PAGE_IMAGE_COUNT}페이지까지 등록할 수 있어요.`,
      )
    } else if (!hasStartingPageNumber && acceptedFiles.length > 0) {
      setUploadMessage('각 이미지에 해당하는 페이지 번호를 입력해 주세요.')
    } else {
      setUploadMessage('')
    }

    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    addPageImages(event.target.files)
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault()
    setIsDragging(false)
    addPageImages(event.dataTransfer.files)
  }

  function updatePageNumber(pageId: string, pageNumber: string) {
    setPages((currentPages) =>
      currentPages.map((page) =>
        page.id === pageId ? { ...page, pageNumber } : page,
      ),
    )
    setIsRegistrationReady(false)
  }

  function removePage(pageId: string) {
    setPages((currentPages) => {
      const targetPage = currentPages.find(({ id }) => id === pageId)
      if (targetPage) {
        URL.revokeObjectURL(targetPage.previewUrl)
        previewUrlsRef.current.delete(targetPage.previewUrl)
      }
      return currentPages.filter(({ id }) => id !== pageId)
    })
    setUploadMessage('')
    setIsRegistrationReady(false)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!isReady) return

    setIsRegistrationReady(true)
  }

  function getFormHint() {
    if (pages.length === 0) {
      return '책 제목과 페이지 이미지를 등록해 주세요.'
    }
    if (hasEmptyPageNumber) {
      return '모든 이미지의 페이지 번호를 입력해 주세요.'
    }
    if (hasDuplicatePageNumber) {
      return '중복된 페이지 번호를 확인해 주세요.'
    }
    if (!bookTitle.trim()) {
      return '책 제목을 입력해 주세요.'
    }
    return `${pages.length}개 페이지를 텍스트로 변환할 준비가 됐어요.`
  }

  return (
    <div className={styles.page}>
      <div className={styles.breadcrumb} aria-label="현재 위치">
        <Link to={appPaths.home}>홈</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">새 책 등록</span>
      </div>

      <header className={styles.pageHeader}>
        <div>
          <p className={styles.eyebrow}>ADD A NEW BOOK</p>
          <h1 className={styles.title}>책을 등록하고, 페이지를 쌓아보세요.</h1>
        </div>
        <p className={styles.intro}>
          페이지 이미지를 한 장씩 등록하면 글자를 인식해 책의 원문으로
          저장합니다. 독서 세션은 등록된 책에서 시작할 수 있어요.
        </p>
      </header>

      <div className={styles.layout}>
        <form className={styles.form} onSubmit={handleSubmit}>
          <section className={styles.formSection} aria-labelledby="book-info-title">
            <div className={styles.sectionNumber} aria-hidden="true">
              01
            </div>
            <div className={styles.sectionContent}>
              <div className={styles.sectionHeader}>
                <div>
                  <h2 id="book-info-title">책 정보</h2>
                  <p>페이지와 독서 기록이 한 권의 책 아래에 모입니다.</p>
                </div>
                <span className={styles.requiredNote}>제목 필수</span>
              </div>

              <div className={styles.bookFields}>
                <label className={styles.field}>
                  <span>책 제목</span>
                  <input
                    autoComplete="off"
                    maxLength={80}
                    onChange={(event) => {
                      setBookTitle(event.target.value)
                      setIsRegistrationReady(false)
                    }}
                    placeholder="예: 생각에 관한 생각"
                    required
                    type="text"
                    value={bookTitle}
                  />
                </label>

                <label className={styles.field}>
                  <span>
                    저자 <small>선택</small>
                  </span>
                  <input
                    autoComplete="off"
                    maxLength={60}
                    onChange={(event) => {
                      setAuthor(event.target.value)
                      setIsRegistrationReady(false)
                    }}
                    placeholder="예: 대니얼 카너먼"
                    type="text"
                    value={author}
                  />
                </label>
              </div>
            </div>
          </section>

          <section className={styles.formSection} aria-labelledby="page-image-title">
            <div className={styles.sectionNumber} aria-hidden="true">
              02
            </div>
            <div className={styles.sectionContent}>
              <div className={styles.sectionHeader}>
                <div>
                  <h2 id="page-image-title">페이지 이미지</h2>
                  <p>이미지 한 장을 책의 한 페이지로 인식해 텍스트로 변환합니다.</p>
                </div>
                <span className={styles.imageCount}>
                  {pages.length}/{MAX_PAGE_IMAGE_COUNT}
                </span>
              </div>

              <div className={styles.pageNumberSeed}>
                <label htmlFor="first-page-number">첫 이미지의 페이지 번호</label>
                <div>
                  <input
                    id="first-page-number"
                    inputMode="numeric"
                    min="1"
                    onChange={(event) => setFirstPageNumber(event.target.value)}
                    placeholder="예: 42"
                    type="number"
                    value={firstPageNumber}
                  />
                  <span>쪽부터</span>
                </div>
                <p>여러 장을 선택하면 파일 순서대로 연속 번호를 붙여요.</p>
              </div>

              <label
                className={`${styles.dropzone} ${isDragging ? styles.dropzoneActive : ''}`}
                onDragEnter={() => setIsDragging(true)}
                onDragLeave={() => setIsDragging(false)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={handleDrop}
              >
                <input
                  accept="image/*"
                  className={styles.fileInput}
                  multiple
                  onChange={handleFileChange}
                  ref={fileInputRef}
                  type="file"
                />
                <span className={styles.uploadIcon} aria-hidden="true">
                  <svg viewBox="0 0 24 24">
                    <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14.5V19a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4.5" />
                  </svg>
                </span>
                <strong>페이지 이미지를 선택하세요</strong>
                <span>한 이미지에 한 페이지만 담아주세요 · JPG, PNG, HEIC</span>
              </label>

              {uploadMessage && (
                <p className={styles.uploadMessage} role="alert">
                  {uploadMessage}
                </p>
              )}

              {pages.length > 0 && (
                <ol className={styles.pageList} aria-label="등록할 책 페이지">
                  {pages.map(({ file, id, pageNumber, previewUrl }, index) => (
                    <li key={id}>
                      <img
                        alt={pageNumber ? `${pageNumber}쪽 미리보기` : `${index + 1}번째 페이지 미리보기`}
                        src={previewUrl}
                      />
                      <div className={styles.pageMeta}>
                        <strong>{file.name}</strong>
                        <span>{formatFileSize(file.size)}</span>
                        <span className={styles.ocrStatus}>
                          {isRegistrationReady ? '텍스트 변환 준비' : '인식 대기'}
                        </span>
                      </div>
                      <label className={styles.pageNumberField}>
                        <span>{index + 1}번째 이미지 페이지</span>
                        <div>
                          <input
                            inputMode="numeric"
                            min="1"
                            onChange={(event) => updatePageNumber(id, event.target.value)}
                            placeholder="번호"
                            required
                            type="number"
                            value={pageNumber}
                          />
                          <span>쪽</span>
                        </div>
                      </label>
                      <button type="button" onClick={() => removePage(id)}>
                        삭제
                      </button>
                    </li>
                  ))}
                </ol>
              )}
            </div>
          </section>

          <div className={styles.formFooter}>
            <p>{getFormHint()}</p>
            <button
              className={styles.submitButton}
              disabled={!isReady || isRegistrationReady}
              type="submit"
            >
              {isRegistrationReady ? '등록 준비 완료' : '책 등록 및 텍스트 변환'}
              <span aria-hidden="true">→</span>
            </button>
          </div>

          {isRegistrationReady && (
            <div className={styles.preparedNotice} role="status">
              <span aria-hidden="true">✓</span>
              <div>
                <strong>
                  책과 {pages.length}개의 페이지가 등록될 준비를 마쳤어요.
                </strong>
                <p>저장된 이미지는 페이지별 텍스트로 변환되어 책에 쌓입니다.</p>
              </div>
            </div>
          )}
        </form>

        <aside className={styles.recognitionGuide} aria-labelledby="recognition-guide-title">
          <p className={styles.guideEyebrow}>PAGE TO TEXT</p>
          <h2 id="recognition-guide-title">
            이미지 한 장이
            <br />
            한 페이지가 됩니다.
          </h2>
          <ol>
            {recognitionSteps.map(({ description, label }, index) => (
              <li key={label}>
                <span>{String(index + 1).padStart(2, '0')}</span>
                <div>
                  <strong>{label}</strong>
                  <small>{description}</small>
                </div>
              </li>
            ))}
          </ol>
          <div className={styles.guideNote}>
            <span aria-hidden="true">✦</span>
            <p>
              지금 전부 등록하지 않아도 괜찮아요. 책을 읽어가며 페이지를
              계속 추가할 수 있습니다.
            </p>
          </div>
        </aside>
      </div>
    </div>
  )
}
