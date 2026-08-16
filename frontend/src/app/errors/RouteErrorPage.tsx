import {
  isRouteErrorResponse,
  Link,
  useRouteError,
} from 'react-router-dom'

import { appPaths } from '@/app/router/paths'

import styles from './RouteState.module.css'

function getStatus(error: unknown) {
  return isRouteErrorResponse(error) ? error.status : 500
}

export function RouteErrorPage() {
  const error = useRouteError()
  const status = getStatus(error)

  return (
    <main className={styles.page}>
      <section className={styles.content} aria-labelledby="route-error-title">
        <p className={styles.status}>{status}</p>
        <h1 className={styles.title} id="route-error-title">
          요청을 처리하지 못했습니다
        </h1>
        <p className={styles.description}>
          잠시 후 다시 시도하거나 홈으로 돌아가 주세요.
        </p>
        <Link className={styles.link} to={appPaths.home}>
          홈으로 돌아가기
        </Link>
      </section>
    </main>
  )
}
