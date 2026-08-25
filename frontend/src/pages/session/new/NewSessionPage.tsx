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

import styles from './NewSessionPage.module.css'

const MAX_IMAGE_COUNT = 8

const trainingSteps = [
  { duration: '20분', label: '읽기' },
  { duration: '5분', label: '구조화' },
  { duration: '5분', label: '글 요약' },
  { duration: '3분', label: '말하기' },
] as const

type ReadingImage = {
  file: File
  id: string
  previewUrl: string
}

function formatFileSize(size: number) {
  if (size < 1024 * 1024) {
    return `${Math.max(1, Math.round(size / 1024))}KB`
  }

  return `${(size / (1024 * 1024)).toFixed(1)}MB`
}

export function NewSessionPage() {
  const [bookTitle, setBookTitle] = useState('')
  const [startPage, setStartPage] = useState('')
  const [endPage, setEndPage] = useState('')
  const [images, setImages] = useState<ReadingImage[]>([])
  const [isDragging, setIsDragging] = useState(false)
  const [uploadMessage, setUploadMessage] = useState('')
  const [isPrepared, setIsPrepared] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const previewUrlsRef = useRef(new Set<string>())

  const isReady = bookTitle.trim().length > 0 && images.length > 0

  useEffect(() => {
    const previewUrls = previewUrlsRef.current

    return () => {
      previewUrls.forEach((previewUrl) => URL.revokeObjectURL(previewUrl))
    }
  }, [])

  function addImages(fileList: FileList | null) {
    if (!fileList) return

    const selectedFiles = Array.from(fileList)
    const imageFiles = selectedFiles.filter((file) =>
      file.type.startsWith('image/'),
    )
    const remainingSlots = MAX_IMAGE_COUNT - images.length
    const acceptedFiles = imageFiles.slice(0, remainingSlots)

    if (acceptedFiles.length > 0) {
      const nextImages = acceptedFiles.map((file) => {
        const previewUrl = URL.createObjectURL(file)
        previewUrlsRef.current.add(previewUrl)

        return {
          file,
          id: crypto.randomUUID(),
          previewUrl,
        }
      })

      setImages((currentImages) => [...currentImages, ...nextImages])
      setIsPrepared(false)
    }

    if (imageFiles.length !== selectedFiles.length) {
      setUploadMessage('이미지 파일만 등록할 수 있어요.')
    } else if (imageFiles.length > remainingSlots) {
      setUploadMessage(`페이지 이미지는 최대 ${MAX_IMAGE_COUNT}장까지 등록할 수 있어요.`)
    } else {
      setUploadMessage('')
    }

    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    addImages(event.target.files)
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault()
    setIsDragging(false)
    addImages(event.dataTransfer.files)
  }

  function removeImage(imageId: string) {
    setImages((currentImages) => {
      const targetImage = currentImages.find(({ id }) => id === imageId)
      if (targetImage) {
        URL.revokeObjectURL(targetImage.previewUrl)
        previewUrlsRef.current.delete(targetImage.previewUrl)
      }
      return currentImages.filter(({ id }) => id !== imageId)
    })
    setUploadMessage('')
    setIsPrepared(false)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!isReady) return

    setIsPrepared(true)
  }

  return (
    <div className={styles.page}>
      <div className={styles.breadcrumb} aria-label="현재 위치">
        <Link to={appPaths.home}>홈</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">새 독서 세션</span>
      </div>

      <header className={styles.pageHeader}>
        <div>
          <p className={styles.eyebrow}>NEW READING SESSION</p>
          <h1 className={styles.title}>오늘 읽을 부분을 준비해 주세요.</h1>
        </div>
        <p className={styles.intro}>
          읽을 책과 페이지를 등록하면, 20분 독서부터 내 언어로 설명하는
          과정까지 차례로 이어집니다.
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
                  <h2 id="book-info-title">읽을 책</h2>
                  <p>기록에서 다시 알아볼 수 있도록 책 정보를 남겨주세요.</p>
                </div>
                <span className={styles.requiredNote}>필수</span>
              </div>

              <label className={styles.field}>
                <span>책 제목</span>
                <input
                  autoComplete="off"
                  maxLength={80}
                  onChange={(event) => {
                    setBookTitle(event.target.value)
                    setIsPrepared(false)
                  }}
                  placeholder="예: 생각에 관한 생각"
                  required
                  type="text"
                  value={bookTitle}
                />
              </label>

              <fieldset className={styles.pageRange}>
                <legend>읽을 페이지 <span>선택</span></legend>
                <label>
                  <span className={styles.srOnly}>시작 페이지</span>
                  <input
                    inputMode="numeric"
                    min="1"
                    onChange={(event) => setStartPage(event.target.value)}
                    placeholder="시작"
                    type="number"
                    value={startPage}
                  />
                  <span>쪽</span>
                </label>
                <span className={styles.rangeDivider} aria-hidden="true">—</span>
                <label>
                  <span className={styles.srOnly}>마지막 페이지</span>
                  <input
                    inputMode="numeric"
                    min="1"
                    onChange={(event) => setEndPage(event.target.value)}
                    placeholder="마지막"
                    type="number"
                    value={endPage}
                  />
                  <span>쪽</span>
                </label>
              </fieldset>
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
                  <p>AI가 나중에 내 요약과 비교할 수 있도록 읽을 부분을 등록해요.</p>
                </div>
                <span className={styles.imageCount}>{images.length}/{MAX_IMAGE_COUNT}</span>
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
                <strong>이미지를 끌어놓거나 선택하세요</strong>
                <span>JPG, PNG, HEIC · 최대 {MAX_IMAGE_COUNT}장</span>
              </label>

              {uploadMessage && (
                <p className={styles.uploadMessage} role="alert">
                  {uploadMessage}
                </p>
              )}

              {images.length > 0 && (
                <ul className={styles.imageList} aria-label="등록한 페이지 이미지">
                  {images.map(({ file, id, previewUrl }, index) => (
                    <li key={id}>
                      <img alt="" src={previewUrl} />
                      <div>
                        <strong>페이지 이미지 {index + 1}</strong>
                        <span>{file.name} · {formatFileSize(file.size)}</span>
                      </div>
                      <button type="button" onClick={() => removeImage(id)}>
                        삭제
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

          <div className={styles.formFooter}>
            <p>
              {isReady
                ? '준비가 끝났어요. 첫 단계는 20분 독서입니다.'
                : '책 제목과 페이지 이미지를 등록하면 시작할 수 있어요.'}
            </p>
            <button className={styles.submitButton} disabled={!isReady} type="submit">
              세션 만들고 시작하기
              <span aria-hidden="true">→</span>
            </button>
          </div>

          {isPrepared && (
            <div className={styles.preparedNotice} role="status">
              <span aria-hidden="true">✓</span>
              <div>
                <strong>독서 세션이 준비됐어요.</strong>
                <p>등록한 페이지를 곁에 두고, 핵심 주장을 생각하며 읽어보세요.</p>
              </div>
            </div>
          )}
        </form>

        <aside className={styles.sessionGuide} aria-labelledby="session-guide-title">
          <p className={styles.guideEyebrow}>TODAY'S ROUTINE</p>
          <h2 id="session-guide-title">33분 동안<br />생각을 꺼내는 훈련</h2>
          <ol>
            {trainingSteps.map(({ duration, label }, index) => (
              <li key={label}>
                <span>{String(index + 1).padStart(2, '0')}</span>
                <strong>{label}</strong>
                <time>{duration}</time>
              </li>
            ))}
          </ol>
          <div className={styles.guideNote}>
            <span aria-hidden="true">✦</span>
            <p>
              AI는 먼저 답을 보여주지 않아요. 내가 생각하고 표현한 뒤에
              구체적인 피드백을 제공합니다.
            </p>
          </div>
        </aside>
      </div>
    </div>
  )
}
