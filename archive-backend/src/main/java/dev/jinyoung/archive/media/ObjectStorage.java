package dev.jinyoung.archive.media;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 객체 스토리지에 대한 이 프로젝트의 유일한 창구.
 *
 * <p>여기서 다루는 것은 <b>키와 바이트뿐</b>이다. 해시로 키를 정하는 규칙은
 * {@link StorageKeys}, 중복을 걸러내는 판단은 {@link ContentAddressedStore} 에 있다.
 * 세 층을 나눈 이유는 각각 다른 방식으로 검증되기 때문이다 — 키 규칙은 컨테이너 없이
 * 단위 테스트로, 이 인터페이스의 구현은 실제 MinIO 위에서, CAS 의 판단은 불변식으로.
 *
 * <p>구현은 S3 API 만 가정한다. MinIO 는 그 API 의 한 구현일 뿐이고, 실제 S3 로 옮길 때
 * 바뀌는 것은 엔드포인트와 자격뿐이어야 한다 (ADR A17).
 *
 * <p><b>실패는 전부 {@link StorageException} 이다.</b> 네트워크 경계라 실패가 예외적 사건이
 * 아니라 정상 경로의 일부이고, 검사 예외로 만들면 워커의 stage 코드가 try-catch 로 덮인다.
 * 재시도 가능 여부의 분류(pipeline.md §5)는 이 예외를 받는 쪽이 아니라 여기서 판단해
 * 붙여줄 자리다 — 그 정보를 아는 것은 SDK 예외를 보고 있는 구현체뿐이다.
 */
public interface ObjectStorage {

    /**
     * 파일을 올린다. {@code checksum} 은 옵션이 아니라 필수다.
     *
     * <p>서버가 수신한 바이트로 SHA-256 을 직접 계산해 이 값과 비교하고, 다르면 저장을
     * 거부한다. 전송 중 손상이 조용히 저장되는 경로를 스토리지가 막아 주는 것이므로
     * (I1), 호출자가 해시를 이미 갖고 있는 상황에서 넘기지 않을 이유가 없다.
     */
    void put(String key, Path source, String contentType, ContentHash checksum);

    /** 메모리에 있는 바이트를 올린다. 파생물처럼 작고 이미 손에 든 결과물에 쓴다. */
    void put(String key, byte[] content, String contentType, ContentHash checksum);

    /**
     * 객체를 스트림으로 연다. <b>호출자가 닫아야 한다.</b>
     *
     * @throws ObjectNotFoundException 키가 없을 때
     */
    InputStream open(String key);

    /** 없으면 {@link Optional#empty()}. "있는지" 와 "얼마나 큰지" 를 한 번의 왕복으로 얻는다. */
    Optional<ObjectStat> stat(String key);

    default boolean exists(String key) {
        return stat(key).isPresent();
    }

    /** 없는 키를 지우는 것도 성공이다. 정리 배치가 두 번 돌아도 문제가 없어야 한다. */
    void delete(String key);

    /**
     * 접두사 아래의 키 전체. 사전순으로 돌려준다.
     *
     * <p>정렬을 계약에 넣는 것은 결정론 때문이다 (roadmap.md §5). 파일시스템 순회와 달리
     * S3 의 목록 순서는 규격상 사전순이므로, 이 계약은 구현에 부담을 주지 않으면서
     * 테스트가 순서에 의존해도 안전하게 만든다.
     */
    List<String> listKeys(String prefix);
}
