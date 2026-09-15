package com.kksg.applicationServices.scm.secret.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.secret.entity.ScmSecret;

public interface ScmSecretRepository extends JpaRepository<ScmSecret, Integer> {

    Optional<ScmSecret> findByReference(String reference);

    void deleteByReference(String reference);
}
