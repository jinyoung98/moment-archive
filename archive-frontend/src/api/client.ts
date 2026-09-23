// 서버 호출의 공통 창구. 두 가지 일괄 처리.
//
// 1. 쿠키 세션 — credentials: 'include'. 개발 프록시가 단일 오리진으로 묶지만(vite.config) 명시.
// 2. CSRF — 백엔드가 XSRF-TOKEN 쿠키(httpOnly 아님) 발행, 변경 요청(POST/PUT/DELETE)은 같은 값을
//    X-XSRF-TOKEN 헤더로 반환 요구 (더블서밋, SecurityConfig).

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length));
    }
  }
  return null;
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/** 미인증(401) 분기용 — 상태코드 실어 던짐. */
export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'HttpError';
  }
}

export async function api(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);

  if (!SAFE_METHODS.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers.set('X-XSRF-TOKEN', token);
    }
  }

  const res = await fetch(path, { ...init, method, headers, credentials: 'include' });
  if (!res.ok) {
    throw new HttpError(res.status, `${method} ${path} → ${res.status}`);
  }
  return res;
}
