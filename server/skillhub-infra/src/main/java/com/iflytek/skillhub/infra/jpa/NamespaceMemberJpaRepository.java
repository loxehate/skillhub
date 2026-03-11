package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NamespaceMemberJpaRepository
        extends JpaRepository<NamespaceMember, Long>, NamespaceMemberRepository {
    Optional<NamespaceMember> findByNamespaceIdAndUserId(Long namespaceId, Long userId);
    List<NamespaceMember> findByUserId(Long userId);
    Page<NamespaceMember> findByNamespaceId(Long namespaceId, Pageable pageable);
    void deleteByNamespaceIdAndUserId(Long namespaceId, Long userId);
}
