package com.iflytek.skillhub.domain.namespace;

import java.util.List;
import java.util.Optional;

public interface NamespaceMemberRepository {
    Optional<NamespaceMember> findByNamespaceIdAndUserId(Long namespaceId, Long userId);
    List<NamespaceMember> findByUserId(Long userId);
    NamespaceMember save(NamespaceMember member);
}
