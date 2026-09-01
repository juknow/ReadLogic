import { afterEach, describe, expect, it, vi } from 'vitest'

import { requestApi, resolveApiUrl } from './apiClient'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('apiClient', () => {
  it('상대 API 경로를 기본 서버 주소로 변환한다', () => {
    expect(resolveApiUrl('/api/books')).toBe('http://localhost:8080/api/books')
    expect(resolveApiUrl('api/books')).toBe('http://localhost:8080/api/books')
    expect(resolveApiUrl('https://images.example/page.png')).toBe(
      'https://images.example/page.png',
    )
  })

  it('서버 공통 오류 응답을 ApiError로 변환한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json(
          {
            code: 'INVALID_REQUEST',
            fieldErrors: { title: '제목은 필수입니다.' },
            message: '입력값을 확인해 주세요.',
          },
          { status: 400 },
        ),
      ),
    )

    await expect(requestApi('/api/books')).rejects.toMatchObject({
      code: 'INVALID_REQUEST',
      fieldErrors: { title: '제목은 필수입니다.' },
      message: '입력값을 확인해 주세요.',
      status: 400,
    })
  })

  it('네트워크 실패를 구분해 전달한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

    await expect(requestApi('/api/books')).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      message: '서버에 연결할 수 없습니다.',
      status: 0,
    })
  })
})
