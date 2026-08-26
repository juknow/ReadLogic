import { Link } from 'react-router-dom'

import { appPaths } from '@/app/router/paths'

import styles from './AppHeader.module.css'

export function AppHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <Link
          className={styles.brand}
          to={appPaths.home}
          aria-label="ReadLogic 홈"
        >
          ReadLogic
        </Link>
        <nav className={styles.navigation} aria-label="주요 탐색">
          <Link className={styles.navigationLink} to={appPaths.books}>
            내 책
          </Link>
          <Link className={styles.primaryAction} to={appPaths.newBook}>
            새 책 등록
          </Link>
        </nav>
      </div>
    </header>
  )
}
