import { api, HttpError } from './client';

// 호출부가 client 를 따로 import 안 하게 되노출 — media 가 서버 접점 단일 창구.
export { HttpError };

// 백엔드 응답 계약. 서버 record 와 이름·모양 일치.
// MeController.Me / UploadOriginalResponse.

export interface Me {
  id: string;
  email: string;
  displayName: string;
}

// MediaAssetStatus 대응. 화면은 THUMBED 여부만 보지만 전 상태 수용.
export type MediaAssetStatus =
  | 'INGESTED'
  | 'PROBED'
  | 'THUMBED'
  | 'READY'
  | 'FAILED';

export interface UploadResult {
  assetId: string;
  status: MediaAssetStatus;
  reused: boolean;
  thumbKey: string | null;
}

/** 로그인 사용자. 미인증이면 HttpError(401) — 호출부가 로그인 유도. */
export async function fetchMe(): Promise<Me> {
  const res = await api('/api/me');
  return res.json();
}

/** 단일 파일 업로드 (청크·세션 없음). 처리 전 즉시 응답. multipart 필드명 'file'(백엔드 기대값). */
export async function uploadMedia(file: File): Promise<UploadResult> {
  const form = new FormData();
  form.append('file', file);
  const res = await api('/media', { method: 'PUT', body: form });
  return res.json();
}

export interface MediaStatus {
  assetId: string;
  status: MediaAssetStatus;
  // 살아 있는 처리 작업 유무. false 인데 THUMBED 가 아니면 멈춘 것(libvips 부재 등).
  processing: boolean;
}

/** 처리가 끝난(더 바뀌지 않을) 상태. */
export function isSettled(status: MediaAssetStatus): boolean {
  return status === 'THUMBED' || status === 'READY' || status === 'FAILED';
}

/** 자산 처리 상태. 처리는 워커가 비동기로 — 업로드 응답은 대개 INGESTED. */
export async function fetchMediaStatus(assetId: string): Promise<MediaStatus> {
  const res = await api(`/media/${assetId}`);
  return res.json();
}

/** 썸네일 바이트 URL. thumbKey(스토리지 좌표) 대신 assetId 기준 서빙 경로. */
export function thumbnailUrl(assetId: string): string {
  return `/media/${assetId}/thumbnail`;
}

/** 브라우저를 구글 로그인으로. 성공 후 Spring 이 '/' 로 복귀. */
export function startLogin(): void {
  window.location.href = '/oauth2/authorization/google';
}
