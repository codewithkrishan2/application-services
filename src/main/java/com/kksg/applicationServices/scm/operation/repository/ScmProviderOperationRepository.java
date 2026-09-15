package com.kksg.applicationServices.scm.operation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;

public interface ScmProviderOperationRepository extends JpaRepository<ScmProviderOperation, Integer> {

    Optional<ScmProviderOperation> findByProviderIdAndOperationCode(Integer providerId,
                                                                   ScmOperationCode operationCode);

    List<ScmProviderOperation> findByProviderId(Integer providerId);

    List<ScmProviderOperation> findByProviderIdAndActiveTrue(Integer providerId);

    void deleteByProviderId(Integer providerId);
}
