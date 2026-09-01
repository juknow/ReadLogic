const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()

export const apiBaseUrl = (configuredApiBaseUrl || 'http://localhost:8080').replace(
  /\/$/,
  '',
)

type ApiErrorBody = {
  code?: string
  fieldErrors?: Record<string, string>
  message?: string
}

export class ApiError extends Error {
  readonly code: string
  readonly fieldErrors: Record<string, string>
  readonly status: number

  constructor(
    message: string,
    options: {
      cause?: unknown
      code?: string
      fieldErrors?: Record<string, string>
      status?: number
    } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'ApiError'
    this.code = options.code ?? 'UNKNOWN_ERROR'
    this.fieldErrors = options.fieldErrors ?? {}
    this.status = options.status ?? 0
  }
}

export function resolveApiUrl(path: string) {
  if (/^https?:\/\//i.test(path)) return path
  return `${apiBaseUrl}${path.startsWith('/') ? path : `/${path}`}`
}

export async function requestApi<T>(path: string, init?: RequestInit) {
  let response: Response

  try {
    response = await fetch(resolveApiUrl(path), init)
  } catch (cause) {
    throw new ApiError('서버에 연결할 수 없습니다.', {
      cause,
      code: 'NETWORK_ERROR',
      status: 0,
    })
  }

  if (!response.ok) {
    const errorBody = await readErrorBody(response)
    throw new ApiError(
      errorBody.message || '요청을 처리하지 못했습니다.',
      {
        code: errorBody.code,
        fieldErrors: errorBody.fieldErrors,
        status: response.status,
      },
    )
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

async function readErrorBody(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody
  } catch {
    return {}
  }
}
