import styles from './HomePage.module.css'

export function HomePage() {
  return (
    <section className={styles.page} aria-labelledby="home-title">
      <h1 className={styles.title} id="home-title">
        ReadLogic
      </h1>
    </section>
  )
}
