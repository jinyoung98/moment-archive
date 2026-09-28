import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  fetchMediaStatus,
  isSettled,
  thumbnailUrl,
  uploadMedia,
  type UploadResult,
} from '../api/media';

// 폴링 간격·상한. 사진 한 장 처리는 1초 안쪽이라 짧게. 상한은 워커가 꺼져 있을 때 무한 폴링 방지.
// SSE(pipeline.md §7)가 붙으면 이 폴링은 폴백으로 내려감.
const POLL_MS = 1_000;
const POLL_LIMIT_MS = 60_000;

// 파일 하나 선택 → PUT /media → 즉시 응답(대개 INGESTED) → 처리 끝날 때까지 상태 폴링 → 썸네일.
// 목록 key 용 업로드 순번. 같은 파일 재업로드는 assetId 가 같아 key 로 못 씀.
let uploadSeq = 0;

type Uploaded = UploadResult & { key: number };

export function Uploader() {
  const [results, setResults] = useState<Uploaded[]>([]);

  const upload = useMutation({
    mutationFn: uploadMedia,
    onSuccess: (result) => setResults((prev) => [{ ...result, key: ++uploadSeq }, ...prev]),
  });

  function onPick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) {
      upload.mutate(file);
    }
    event.target.value = ''; // 같은 파일 재선택도 change 재발화
  }

  return (
    <section>
      <h2>사진 올리기</h2>
      <input type="file" accept="image/*" onChange={onPick} disabled={upload.isPending} />
      {upload.isPending && <p>업로드 중…</p>}
      {upload.isError && <p>업로드 실패: {String(upload.error)}</p>}

      <ul>
        {results.map((r) => (
          <li key={r.key}>
            <ResultItem result={r} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function ResultItem({ result }: { result: UploadResult }) {
  const [startedAt] = useState(() => Date.now());

  // 이미 끝난 상태(중복 업로드 등)면 조회 없이 응답값 그대로.
  const status = useQuery({
    queryKey: ['media-status', result.assetId],
    queryFn: () => fetchMediaStatus(result.assetId),
    enabled: !isSettled(result.status),
    // 업로드 직후엔 방금 작업을 등록했으므로 processing=true 로 시작.
    initialData: { assetId: result.assetId, status: result.status, processing: !isSettled(result.status) },
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && (isSettled(data.status) || !data.processing)) return false;
      return Date.now() - startedAt < POLL_LIMIT_MS ? POLL_MS : false;
    },
  });

  const current = status.data.status;
  const stalled = !isSettled(current) && !status.data.processing;
  const hasThumb = current === 'THUMBED' || current === 'READY';

  return (
    <figure>
      {hasThumb ? (
        <img src={thumbnailUrl(result.assetId)} alt="썸네일" width={256} />
      ) : isSettled(current) || stalled ? (
        // FAILED, 또는 작업이 끝났는데 THUMBED 못 감(libvips 부재로 THUMB DEAD 등).
        <span>썸네일 없음 ({current})</span>
      ) : (
        // 처리 중. 폴링 상한을 넘겨도 여기 남음 — 워커가 꺼져 있을 가능성.
        <span>처리 중… ({current})</span>
      )}
      <figcaption>
        {result.reused ? '이미 있던 사진' : '새 사진'} · {current}
      </figcaption>
    </figure>
  );
}
