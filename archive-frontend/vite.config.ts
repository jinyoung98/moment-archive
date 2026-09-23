import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버(:5173) → 백엔드(:8080) 프록시 경로.
//
// changeOrigin: false 가 핵심. Host 헤더를 localhost:5173 로 보존 → Spring 이 OAuth redirect_uri·
// 세션·XSRF 쿠키를 모두 :5173 기준으로 생성. OAuth 왕복 전체가 단일 오리진 → 크로스오리진 쿠키
// 문제 소멸. (Google Console 리다이렉트 URI 에 http://localhost:5173/login/oauth2/code/google 등록
// 필요 — docs/progress.md.)
//
// '/' 는 프록시 안 함 — SPA 가 :5173 서빙, 로그인 성공 후 Spring 의 '/' 리다이렉트가 그 자리로 복귀.
const backend = 'http://localhost:8080';
const proxied = ['/api', '/media', '/oauth2', '/login', '/logout'];

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: Object.fromEntries(
      proxied.map((path) => [path, { target: backend, changeOrigin: false }]),
    ),
  },
});
