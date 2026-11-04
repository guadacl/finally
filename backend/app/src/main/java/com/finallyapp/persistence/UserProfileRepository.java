package com.finallyapp.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, String> {

    /**
     * Creates the profile ($10,000 cash) unless it exists; returns 1 when created, 0 otherwise. Native because it
     * must be race-free: the browser fires several API calls at a user's first login.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO users_profile (id) VALUES (:id) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int insertIfMissing(@Param("id") String id);

    /** The profile with a row lock ({@code SELECT ... FOR UPDATE}); call inside a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserProfileEntity p where p.id = :id")
    Optional<UserProfileEntity> findForUpdate(@Param("id") String id);

    @Query("select p.id from UserProfileEntity p order by p.createdAt")
    List<String> findAllIdsOrderByCreatedAt();

    /**
     * Sets only the email (never touches the cash balance a concurrent trade may be changing); returns the number of
     * changed rows (0 when the profile does not exist or the value is unchanged).
     */
    @Modifying
    @Transactional
    @Query("update UserProfileEntity p set p.email = :email where p.id = :id "
            + "and (p.email is null or p.email <> :email)")
    int updateEmail(@Param("id") String id, @Param("email") String email);

    /** Sets or clears ({@code phone} null) only the phone number; returns the number of changed rows. */
    @Modifying
    @Transactional
    @Query("update UserProfileEntity p set p.phone = :phone where p.id = :id "
            + "and ((:phone is null and p.phone is not null) or (:phone is not null and (p.phone is null or p.phone <> :phone)))")
    int updatePhone(@Param("id") String id, @Param("phone") String phone);

    /** Cheap connectivity check for the health endpoint. */
    @Query(value = "SELECT 1", nativeQuery = true)
    Integer ping();
}
