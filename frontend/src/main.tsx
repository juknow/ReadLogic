import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from '@/app/App'
import { clearLegacyBookStorage } from '@/features/books/data/clearLegacyBookStorage'
import '@/shared/styles/index.css'

function renderApplication() {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void clearLegacyBookStorage()
  .then((result) => {
    if (result === 'blocked' || result === 'failed') {
      console.warn('기존 브라우저 책 데이터 삭제를 다음 실행에서 재시도합니다.')
    }
  })
  .catch(() => {
    console.warn('기존 브라우저 책 데이터 삭제를 다음 실행에서 재시도합니다.')
  })
  .finally(renderApplication)
