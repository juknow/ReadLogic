import { createBrowserRouter } from 'react-router-dom'

import { NotFoundPage } from '@/app/errors/NotFoundPage'
import { RouteErrorPage } from '@/app/errors/RouteErrorPage'
import { AppLayout } from '@/app/layouts/AppLayout'
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
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
])
