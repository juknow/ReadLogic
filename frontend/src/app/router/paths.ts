export const appPaths = {
  book: (bookId: string) => `/books/${bookId}`,
  books: '/books',
  home: '/',
  newBook: '/books/new',
} as const
