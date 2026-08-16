import { Link } from 'react-router-dom'

import { appPaths } from '@/app/router/paths'

import styles from './RouteState.module.css'

export function NotFoundPage() {
  return (
    <section className={styles.page} aria-labelledby="not-found-title">
      <div className={styles.content}>
        <p className={styles.status}>404</p>
        <h1 className={styles.title} id="not-found-title">
          페이지를 찾을 수 없습니다
        </h1>
        <p className={styles.description}>
          주소를 다시 확인하거나 홈으로 돌아가 주세요.
        </p>
        <Link className={styles.link} to={appPaths.home}>
          홈으로 돌아가기
        </Link>
      </div>
    </section>
  )
}
