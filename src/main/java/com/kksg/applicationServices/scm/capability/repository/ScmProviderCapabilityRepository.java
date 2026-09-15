package com.kksg.applicationServices.scm.capability.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.capability.entity.ScmProviderCapability;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;

public interface ScmProviderCapabilityRepository extends JpaRepository<ScmProviderCapability, Integer> {

    Optional<ScmProviderCapability> findByProviderIdAndCapabilityCode(Integer providerId,
                                                                     ScmCapabilityCode capabilityCode);

    List<ScmProviderCapability> findByProviderId(Integer providerId);

    List<ScmProviderCapability> findByProviderIdAndSupportedTrue(Integer providerId);

    void deleteByProviderId(Integer providerId);
}
