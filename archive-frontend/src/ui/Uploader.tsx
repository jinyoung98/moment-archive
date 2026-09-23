import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { uploadMedia, thumbnailUrl, type UploadResult } from '../api/media';

// 파일 하나 선택 → PUT /media → 응답 상태에 따라 썸네일 또는 안내. W1 수직 슬라이스의 끝단 —
// "사진 1장 → 썸네일"이 눈에 보이는 자리.
export function Uploader() {
  const [results, setResults] = useState<UploadResult[]>([]);

  const upload = useMutation({
    mutationFn: uploadMedia,
    onSuccess: (result) => setResults((prev) => [result, ...prev]),
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
          <li key={r.assetId}>
            <ResultItem result={r} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function ResultItem({ result }: { result: UploadResult }) {
  const hasThumb = result.status === 'THUMBED' || result.status === 'READY' || result.thumbKey !== null;

  return (
    <figure>
      {hasThumb ? (
        <img src={thumbnailUrl(result.assetId)} alt="썸네일" width={256} />
      ) : (
        // THUMB 미도달: libvips 부재로 PROBED 정지 또는 FAILED. 원본은 이미 저장됨.
        <span>썸네일 없음 ({result.status})</span>
      )}
      <figcaption>
        {result.reused ? '이미 있던 사진' : '새 사진'} · {result.status}
      </figcaption>
    </figure>
  );
}
