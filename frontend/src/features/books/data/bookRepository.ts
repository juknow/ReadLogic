import {
  createBookPage,
  type Book,
  type CreateBookInput,
} from '@/features/books/model/book'

const DATABASE_NAME = 'readlogic'
const DATABASE_VERSION = 1
const BOOK_STORE_NAME = 'books'

function openDatabase() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)

    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(BOOK_STORE_NAME)) {
        database.createObjectStore(BOOK_STORE_NAME, { keyPath: 'id' })
      }
    }

    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new Error('책 저장소를 열 수 없습니다.'))
  })
}

function readRequest<T>(request: IDBRequest<T>) {
  return new Promise<T>((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function completeTransaction(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error)
    transaction.onabort = () => reject(transaction.error)
  })
}

export async function getBooks() {
  const database = await openDatabase()

  try {
    const transaction = database.transaction(BOOK_STORE_NAME, 'readonly')
    const books = await readRequest<Book[]>(
      transaction.objectStore(BOOK_STORE_NAME).getAll(),
    )

    return books.sort((left, right) =>
      right.updatedAt.localeCompare(left.updatedAt),
    )
  } finally {
    database.close()
  }
}

export async function getBook(bookId: string) {
  const database = await openDatabase()

  try {
    const transaction = database.transaction(BOOK_STORE_NAME, 'readonly')
    const book = await readRequest<Book | undefined>(
      transaction.objectStore(BOOK_STORE_NAME).get(bookId),
    )

    return book ?? null
  } finally {
    database.close()
  }
}

export async function createBook(input: CreateBookInput) {
  const timestamp = new Date().toISOString()
  const book: Book = {
    author: input.author.trim(),
    createdAt: timestamp,
    id: crypto.randomUUID(),
    pages: input.pages
      .map(({ file, pageNumber }) => createBookPage(file, pageNumber))
      .sort((left, right) => left.pageNumber - right.pageNumber),
    title: input.title.trim(),
    updatedAt: timestamp,
  }

  await saveBook(book)
  return book
}

export async function saveBook(book: Book) {
  const database = await openDatabase()
  const updatedBook: Book = {
    ...book,
    pages: [...book.pages].sort(
      (left, right) => left.pageNumber - right.pageNumber,
    ),
    updatedAt: new Date().toISOString(),
  }

  try {
    const transaction = database.transaction(BOOK_STORE_NAME, 'readwrite')
    transaction.objectStore(BOOK_STORE_NAME).put(updatedBook)
    await completeTransaction(transaction)
    return updatedBook
  } finally {
    database.close()
  }
}
