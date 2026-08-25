import styles from './TrainingStep.module.css'

type TrainingStepProps = {
  activity: string
  description: string
  duration: string
  order: string
}

export function TrainingStep({
  activity,
  description,
  duration,
  order,
}: TrainingStepProps) {
  return (
    <li className={styles.step}>
      <div className={styles.meta}>
        <span className={styles.order} aria-hidden="true">
          {order}
        </span>
        <span className={styles.duration}>{duration}</span>
      </div>
      <h3 className={styles.activity}>{activity}</h3>
      <p className={styles.description}>{description}</p>
    </li>
  )
}
