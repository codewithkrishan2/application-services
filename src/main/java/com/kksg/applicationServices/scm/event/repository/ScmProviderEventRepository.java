package com.kksg.applicationServices.scm.event.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.event.entity.ScmProviderEvent;

public interface ScmProviderEventRepository extends JpaRepository<ScmProviderEvent, Integer> {

    Optional<ScmProviderEvent> findByProviderIdAndProviderEventNameIgnoreCaseAndProviderActionIgnoreCase(
            Integer providerId, String providerEventName, String providerAction);

    List<ScmProviderEvent> findByProviderId(Integer providerId);

    void deleteByProviderId(Integer providerId);
}
