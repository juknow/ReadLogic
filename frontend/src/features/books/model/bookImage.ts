export const ACCEPTED_BOOK_IMAGE_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

export const ACCEPTED_BOOK_IMAGE_INPUT = ACCEPTED_BOOK_IMAGE_TYPES.join(',')
export const MAX_BOOK_IMAGE_SIZE = 10 * 1024 * 1024

export function getBookImageValidationError(file: File) {
  if (!ACCEPTED_BOOK_IMAGE_TYPES.some((type) => type === file.type)) {
    return 'JPG, PNG, WebP 이미지만 등록할 수 있어요.'
  }
  if (file.size > MAX_BOOK_IMAGE_SIZE) {
    return '이미지 한 장은 10MB를 초과할 수 없어요.'
  }
  return null
}
