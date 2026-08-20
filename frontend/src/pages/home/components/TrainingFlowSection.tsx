import { TrainingStep } from './TrainingStep'
import styles from './TrainingFlowSection.module.css'

const trainingSteps = [
  {
    order: '01',
    duration: '20분',
    activity: '읽기',
    description:
      '알림에 흔들리지 않는 시간 동안 핵심 문장과 떠오르는 질문에 집중합니다.',
  },
  {
    order: '02',
    duration: '5분',
    activity: '구조화',
    description:
      '내용의 주장과 근거, 서로의 관계를 살피며 생각의 뼈대를 세웁니다.',
  },
  {
    order: '03',
    duration: '5분',
    activity: '글 요약',
    description:
      '구조화한 내용을 짧은 글로 압축하며 핵심과 불필요한 정보를 구분합니다.',
  },
  {
    order: '04',
    duration: '3분',
    activity: '말하기',
    description:
      '책을 보지 않고 내 언어로 설명하며 이해한 부분과 막힌 부분을 확인합니다.',
  },
] as const

export function TrainingFlowSection() {
  return (
    <section
      className={styles.section}
      id="training-flow"
      aria-labelledby="training-flow-title"
    >
      <div className={styles.intro}>
        <p className={styles.eyebrow}>33분 훈련 루틴</p>
        <h2 className={styles.title} id="training-flow-title">
          읽고, 정리하고,
          <br />
          설명하며 완성합니다.
        </h2>
        <p className={styles.description}>
          한 번의 독서를 네 단계로 나누어, 막연한 이해가 명확한 설명이 되는
          과정을 반복합니다.
        </p>
      </div>

      <ol className={styles.steps}>
        {trainingSteps.map((step) => (
          <TrainingStep key={step.order} {...step} />
        ))}
      </ol>
    </section>
  )
}
