const LEGACY_DATABASE_NAME = 'readlogic'
const CLEAR_MARKER_KEY = 'readlogic.legacy-book-storage-cleared.v1'

export type LegacyStorageClearResult =
  | 'already-cleared'
  | 'blocked'
  | 'cleared'
  | 'failed'

type ClearLegacyBookStorageOptions = {
  databaseFactory?: IDBFactory
  markerStorage?: Pick<Storage, 'getItem' | 'setItem'>
}

export async function clearLegacyBookStorage(
  options: ClearLegacyBookStorageOptions = {},
): Promise<LegacyStorageClearResult> {
  const databaseFactory = options.databaseFactory ?? globalThis.indexedDB
  const markerStorage = options.markerStorage ?? globalThis.localStorage

  try {
    if (markerStorage.getItem(CLEAR_MARKER_KEY) === '1') {
      return 'already-cleared'
    }
  } catch {
    // 저장소 접근이 제한되어도 기존 데이터 삭제는 계속 시도한다.
  }

  return new Promise((resolve) => {
    let settled = false
    const request = databaseFactory.deleteDatabase(LEGACY_DATABASE_NAME)

    request.onsuccess = () => {
      try {
        markerStorage.setItem(CLEAR_MARKER_KEY, '1')
      } catch {
        // 표식을 남기지 못하면 다음 실행에서 안전하게 삭제를 재시도한다.
      }
      if (!settled) resolve('cleared')
      settled = true
    }
    request.onerror = () => {
      if (!settled) resolve('failed')
      settled = true
    }
    request.onblocked = () => {
      if (!settled) resolve('blocked')
      settled = true
    }
  })
}
