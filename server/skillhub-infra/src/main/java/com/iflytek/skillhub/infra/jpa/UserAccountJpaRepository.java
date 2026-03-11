package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAccountJpaRepository
        extends JpaRepository<UserAccount, Long>, UserAccountRepository {
}
