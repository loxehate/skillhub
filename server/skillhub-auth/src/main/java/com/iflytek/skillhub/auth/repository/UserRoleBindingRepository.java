package com.iflytek.skillhub.auth.repository;

import com.iflytek.skillhub.auth.entity.UserRoleBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserRoleBindingRepository extends JpaRepository<UserRoleBinding, Long> {
    List<UserRoleBinding> findByUserId(Long userId);
}
