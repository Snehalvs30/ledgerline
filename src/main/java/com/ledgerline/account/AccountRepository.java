package com.ledgerline.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Loads the accounts and locks their rows (SELECT ... FOR UPDATE) until the
     * transaction ends. Other transfers touching these accounts must wait.
     *
     * ORDER BY id matters: every transfer locks accounts in the same order, so two
     * transfers (A to B and B to A) can never each hold one lock while waiting for
     * the other. That situation is a deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);
}