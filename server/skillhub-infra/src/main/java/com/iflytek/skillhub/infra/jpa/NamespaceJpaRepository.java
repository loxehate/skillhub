package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NamespaceJpaRepository
        extends JpaRepository<Namespace, Long>, NamespaceRepository {
    Optional<Namespace> findBySlug(String slug);
    Page<Namespace> findByStatus(NamespaceStatus status, Pageable pageable);
}
