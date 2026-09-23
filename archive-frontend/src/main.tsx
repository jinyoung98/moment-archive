import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './ui/App';

// 서버 상태는 TanStack Query (ADR A16). W1 은 /api/me 캐싱뿐이지만, W2 의 SSE·폴링 이중 갱신이
// 선택 이유라 처음부터 이 위에.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 401 은 재시도해도 401 — 로그인 유도가 맞음.
      retry: false,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
);
