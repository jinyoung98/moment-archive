import { useQuery } from '@tanstack/react-query';
import { fetchMe, startLogin, HttpError } from '../api/media';
import { Uploader } from './Uploader';

// 화면 관문: /api/me 로 로그인 상태 확인. 401 이면 로그인 유도, 성공이면 업로더.
// 스타일 없음 (roadmap.md §2 W1 — "파일 선택 + 결과 표시. 스타일 없음").
export function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: fetchMe });

  if (me.isPending) {
    return <p>로그인 상태 확인 중…</p>;
  }

  // 401 = "로그인 안 됨" — 예외 아닌 정상 분기. 그 외 에러만 실제 오류로 표시.
  if (me.isError) {
    if (me.error instanceof HttpError && me.error.status === 401) {
      return (
        <main>
          <h1>아카이브</h1>
          <p>기록을 보려면 로그인하세요.</p>
          <button onClick={startLogin}>구글로 로그인</button>
        </main>
      );
    }
    return (
      <main>
        <h1>아카이브</h1>
        <p>서버에 연결하지 못했습니다: {String(me.error)}</p>
      </main>
    );
  }

  return (
    <main>
      <h1>아카이브</h1>
      <p>
        {me.data.displayName} ({me.data.email})
      </p>
      <Uploader />
    </main>
  );
}
