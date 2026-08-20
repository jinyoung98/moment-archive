package dev.jinyoung.archive.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, UUID> {

    Optional<User> findByProviderAndProviderUid(String provider, String providerUid);
}
