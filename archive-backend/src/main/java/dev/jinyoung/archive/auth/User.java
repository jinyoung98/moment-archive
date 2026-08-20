package dev.jinyoung.archive.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** schema.md §5.2. 자체 회원가입 없음 — 소셜 제공자 신원만 보관. */
@Table("users")
public record User(
        @Id UUID id,
        String provider,
        @Column("provider_uid") String providerUid,
        String email,
        @Column("display_name") String displayName,
        @Column("created_at") Instant createdAt) {
}
