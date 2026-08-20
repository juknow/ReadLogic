import { HeroSection } from './components/HeroSection'
import styles from './HomePage.module.css'

export function HomePage() {
  return (
    <div className={styles.page}>
      <HeroSection />
    </div>
  )
}
