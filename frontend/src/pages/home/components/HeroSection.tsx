import styles from './HeroSection.module.css'

const trainingRhythm = [
  { duration: '20분', activity: '읽기' },
  { duration: '5분', activity: '구조화' },
  { duration: '5분', activity: '글 요약' },
  { duration: '3분', activity: '말하기' },
] as const

export function HeroSection() {
  return (
    <section className={styles.hero} aria-labelledby="home-title">
      <div className={styles.content}>
        <p className={styles.eyebrow}>읽고 끝내지 않는 독서 훈련</p>
        <h1 className={styles.title} id="home-title">
          읽은 것을,
          <br />
          <span>내 언어로 설명하는 힘.</span>
        </h1>
        <p className={styles.description}>
          ReadLogic은 읽은 내용을 구조화하고, 짧게 요약하고, 직접 말로
          설명하며 이해를 단단하게 만드는 독서 훈련입니다.
        </p>
        <a className={styles.primaryAction} href="#training-flow">
          훈련 방식 살펴보기
          <span aria-hidden="true">↓</span>
        </a>
      </div>

      <div
        className={styles.rhythm}
        aria-label="20분 읽기, 5분 구조화, 5분 글 요약, 3분 말하기"
      >
        <p className={styles.rhythmLabel}>한 번의 훈련 · 33분</p>
        <ol className={styles.rhythmSteps} aria-hidden="true">
          {trainingRhythm.map(({ duration, activity }) => (
            <li className={styles.rhythmStep} key={activity}>
              <strong>{duration}</strong>
              <span>{activity}</span>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}
