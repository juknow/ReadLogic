import { Outlet } from 'react-router-dom'

import styles from './AppLayout.module.css'

export function AppLayout() {
  return (
    <div className={styles.layout}>
      <a className={styles.skipLink} href="#main-content">
        본문으로 건너뛰기
      </a>
      <main className={styles.main} id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
