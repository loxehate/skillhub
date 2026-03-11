package com.iflytek.skillhub.domain.namespace;

import java.util.Optional;

public interface NamespaceRepository {
    Optional<Namespace> findById(Long id);
    Optional<Namespace> findBySlug(String slug);
    Namespace save(Namespace namespace);
}
