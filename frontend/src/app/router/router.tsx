import { createBrowserRouter } from 'react-router-dom'

import { NotFoundPage } from '@/app/errors/NotFoundPage'
import { RouteErrorPage } from '@/app/errors/RouteErrorPage'
import { AppLayout } from '@/app/layouts/AppLayout'
import { BookDetailPage } from '@/pages/books/detail/BookDetailPage'
import { BooksPage } from '@/pages/books/list/BooksPage'
import { NewBookPage } from '@/pages/books/new/NewBookPage'
import { HomePage } from '@/pages/home/HomePage'

import { appPaths } from './paths'

export const router = createBrowserRouter([
  {
    path: appPaths.home,
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        index: true,
        element: <HomePage />,
      },
      {
        path: appPaths.newBook,
        element: <NewBookPage />,
      },
      {
        path: appPaths.books,
        element: <BooksPage />,
      },
      {
        path: `${appPaths.books}/:bookId`,
        element: <BookDetailPage />,
      },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
])
