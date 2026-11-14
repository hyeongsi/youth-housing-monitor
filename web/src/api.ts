export type RecordValue = Record<string, any>

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.method && !['GET', 'HEAD', 'OPTIONS'].includes(init.method.toUpperCase())) {
    const token = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token))
  }
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.detail || body.message || '요청을 처리하지 못했습니다.')
  return body as T
}

export const won = (value: unknown) => Number.isFinite(Number(value)) ? `${Number(value).toLocaleString('ko-KR')}원` : '공고문 확인'
export const date = (value: unknown) => value ? String(value).replaceAll('-', '.') : '미기재'
export const formattedDigits = (value: string) => value.replace(/\D/g, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
