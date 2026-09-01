import { describe, expect, it, vi } from 'vitest'

import { clearLegacyBookStorage } from './clearLegacyBookStorage'

type DeleteRequest = Pick<IDBOpenDBRequest, 'onblocked' | 'onerror' | 'onsuccess'>

function createDependencies(markerValue: string | null = null) {
  const request: DeleteRequest = {
    onblocked: null,
    onerror: null,
    onsuccess: null,
  }
  const markerStorage = {
    getItem: vi.fn().mockReturnValue(markerValue),
    setItem: vi.fn(),
  }
  const deleteDatabase = vi.fn().mockReturnValue(request)
  const databaseFactory = { deleteDatabase } as unknown as IDBFactory

  return { databaseFactory, deleteDatabase, markerStorage, request }
}

describe('clearLegacyBookStorage', () => {
  it('기존 readlogic 데이터베이스를 삭제하고 완료 표식을 남긴다', async () => {
    const dependencies = createDependencies()
    const resultPromise = clearLegacyBookStorage(dependencies)

    const onSuccess = dependencies.request.onsuccess as
      | ((event: Event) => void)
      | null
    onSuccess?.(new Event('success'))

    await expect(resultPromise).resolves.toBe('cleared')
    expect(dependencies.deleteDatabase).toHaveBeenCalledWith('readlogic')
    expect(dependencies.markerStorage.setItem).toHaveBeenCalledWith(
      'readlogic.legacy-book-storage-cleared.v1',
      '1',
    )
  })

  it('다른 탭으로 삭제가 막히면 표식 없이 다음 실행 재시도를 허용한다', async () => {
    const dependencies = createDependencies()
    const resultPromise = clearLegacyBookStorage(dependencies)

    const onBlocked = dependencies.request.onblocked as
      | ((event: Event) => void)
      | null
    onBlocked?.(new Event('blocked'))

    await expect(resultPromise).resolves.toBe('blocked')
    expect(dependencies.markerStorage.setItem).not.toHaveBeenCalled()
  })

  it('이미 삭제한 경우 데이터베이스 요청을 반복하지 않는다', async () => {
    const dependencies = createDependencies('1')

    await expect(clearLegacyBookStorage(dependencies)).resolves.toBe(
      'already-cleared',
    )
    expect(dependencies.deleteDatabase).not.toHaveBeenCalled()
  })
})
