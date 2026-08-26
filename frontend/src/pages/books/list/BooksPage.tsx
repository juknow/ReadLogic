import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { appPaths } from '@/app/router/paths'
import { getBooks } from '@/features/books/data/bookRepository'
import { useObjectUrl } from '@/features/books/hooks/useObjectUrl'
import type { Book } from '@/features/books/model/book'

import styles from './BooksPage.module.css'

const dateFormatter = new Intl.DateTimeFormat('ko-KR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

function getPageSummary(book: Book) {
  if (book.pages.length === 0) return '등록된 페이지 없음'

  const sortedPages = [...book.pages].sort(
    (left, right) => left.pageNumber - right.pageNumber,
  )
  const firstPage = sortedPages[0].pageNumber
  const lastPage = sortedPages[sortedPages.length - 1].pageNumber
  const pageRange =
    firstPage === lastPage ? `${firstPage}쪽` : `${firstPage}–${lastPage}쪽`

  return `${pageRange} · ${book.pages.length}페이지`
}

function BookCard({ book }: { book: Book }) {
  const coverUrl = useObjectUrl(book.pages[0]?.image)

  return (
    <li>
      <Link className={styles.bookCard} to={appPaths.book(book.id)}>
        <div className={styles.cover}>
          {coverUrl ? (
            <img alt="" src={coverUrl} />
          ) : (
            <span aria-hidden="true">R</span>
          )}
          <span className={styles.pageCount}>{book.pages.length}</span>
        </div>
        <div className={styles.bookInfo}>
          <p className={styles.bookMeta}>{getPageSummary(book)}</p>
          <h2>{book.title}</h2>
          <p className={styles.author}>{book.author || '저자 미등록'}</p>
          <p className={styles.updatedAt}>
            {dateFormatter.format(new Date(book.updatedAt))} 수정
          </p>
        </div>
        <span className={styles.cardArrow} aria-hidden="true">
          →
        </span>
      </Link>
    </li>
  )
}

export function BooksPage() {
  const [books, setBooks] = useState<Book[] | null>(null)
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    let isActive = true

    void getBooks()
      .then((storedBooks) => {
        if (isActive) setBooks(storedBooks)
      })
      .catch(() => {
        if (isActive) {
          setLoadError('등록한 책을 불러오지 못했습니다. 다시 시도해 주세요.')
        }
      })

    return () => {
      isActive = false
    }
  }, [])

  return (
    <div className={styles.page}>
      <div className={styles.breadcrumb} aria-label="현재 위치">
        <Link to={appPaths.home}>홈</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">내 책</span>
      </div>

      <header className={styles.pageHeader}>
        <div>
          <p className={styles.eyebrow}>MY LIBRARY</p>
          <h1>내 책</h1>
          <p>
            등록한 책과 페이지 원문을 한곳에서 열람하고 관리할 수 있습니다.
          </p>
        </div>
        <Link className={styles.primaryAction} to={appPaths.newBook}>
          새 책 등록
          <span aria-hidden="true">＋</span>
        </Link>
      </header>

      {loadError && (
        <p className={styles.errorState} role="alert">
          {loadError}
        </p>
      )}

      {!loadError && books === null && (
        <p className={styles.loadingState} role="status">
          내 책을 불러오는 중입니다.
        </p>
      )}

      {books?.length === 0 && (
        <section className={styles.emptyState} aria-labelledby="empty-books-title">
          <span className={styles.emptyMark} aria-hidden="true">
            R
          </span>
          <div>
            <h2 id="empty-books-title">아직 등록한 책이 없어요.</h2>
            <p>첫 책의 페이지를 등록하면 이곳에 차곡차곡 모입니다.</p>
          </div>
          <Link to={appPaths.newBook}>첫 책 등록하기 →</Link>
        </section>
      )}

      {books && books.length > 0 && (
        <section className={styles.library} aria-labelledby="library-title">
          <div className={styles.libraryHeader}>
            <h2 id="library-title">등록한 책</h2>
            <span>{books.length}권</span>
          </div>
          <ul className={styles.bookGrid}>
            {books.map((book) => (
              <BookCard book={book} key={book.id} />
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
