import { useEffect, useState } from 'react'

export function useObjectUrl(blob: Blob | null | undefined) {
  const [objectUrl, setObjectUrl] = useState('')

  useEffect(() => {
    if (!blob) {
      setObjectUrl('')
      return
    }

    const nextObjectUrl = URL.createObjectURL(blob)
    setObjectUrl(nextObjectUrl)

    return () => URL.revokeObjectURL(nextObjectUrl)
  }, [blob])

  return objectUrl
}
