package com.kksg.applicationServices.scm.provider.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.provider.entity.ScmProvider;

public interface ScmProviderRepository extends JpaRepository<ScmProvider, Integer> {

    Optional<ScmProvider> findByProviderCodeIgnoreCase(String providerCode);

    boolean existsByProviderCodeIgnoreCase(String providerCode);

    /** Providers offered to users, in presentation order. */
    List<ScmProvider> findByActiveTrueOrderByDisplayOrderAscProviderNameAsc();

    List<ScmProvider> findAllByOrderByDisplayOrderAscProviderNameAsc();
}
