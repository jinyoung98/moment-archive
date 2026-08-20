package dev.jinyoung.archive.media;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 객체 스토리지에 대한 이 프로젝트의 유일한 창구.
 *
 * 대상: 키와 바이트뿐. 키 결정 규칙은 {@link StorageKeys}, 중복 판단은
 * {@link ContentAddressedStore}. 3계층 분리 이유: 검증 방식 차이 — 키 규칙은 컨테이너
 * 없는 단위 테스트, 이 인터페이스 구현은 실제 MinIO, CAS 판단은 불변식.
 *
 * 구현 전제: S3 API 만. MinIO 는 구현체 중 하나, 실제 S3 전환 시 변경 범위는
 * 엔드포인트·자격뿐 (ADR A17).
 *
 * 실패는 전부 {@link StorageException}. 네트워크 경계라 실패는 예외 사건이 아닌 정상
 * 경로 일부, 검사 예외화 시 워커 stage 코드가 try-catch 로 덮임. 재시도 가능 여부 분류
 * (pipeline.md §5)는 여기서 판단·부여 — SDK 예외를 보는 건 구현체뿐.
 */
public interface ObjectStorage {

    /**
     * 파일 업로드. {@code checksum} 필수.
     *
     * 서버가 수신 바이트로 SHA-256 직접 계산·대조, 불일치 시 저장 거부. 전송 중 손상의
     * 무단 저장을 막는 장치 (I1), 미전달 시 이유 없음.
     */
    void put(String key, Path source, String contentType, ContentHash checksum);

    /** 메모리 상의 바이트 업로드. 파생물처럼 작고 이미 확보된 결과물용. */
    void put(String key, byte[] content, String contentType, ContentHash checksum);

    /**
     * 객체 스트림 오픈. 닫기는 호출자 책임.
     *
     * @throws ObjectNotFoundException 키가 없을 때
     */
    InputStream open(String key);

    /** 부재 시 {@link Optional#empty()}. 존재 여부·크기를 한 번의 왕복으로 확인. */
    Optional<ObjectStat> stat(String key);

    default boolean exists(String key) {
        return stat(key).isPresent();
    }

    /** 없는 키 삭제도 성공 처리. 정리 배치 중복 실행 대비. */
    void delete(String key);

    /**
     * 접두사 하위 키 전체, 사전순 반환.
     *
     * 정렬을 계약에 포함한 이유: 결정론 (roadmap.md §5). S3 목록 순서는 규격상 사전순이라
     * 구현 부담 없이 순서 의존 테스트도 안전.
     */
    List<String> listKeys(String prefix);
}
