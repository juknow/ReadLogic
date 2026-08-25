import { HeroSection } from './components/HeroSection'
import { TrainingFlowSection } from './components/TrainingFlowSection'
import styles from './HomePage.module.css'

export function HomePage() {
  return (
    <div className={styles.page}>
      <HeroSection />
      <TrainingFlowSection />
    </div>
  )
}
