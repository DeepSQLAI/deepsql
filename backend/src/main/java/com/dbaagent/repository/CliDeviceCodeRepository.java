package com.dbaagent.repository;

import com.dbaagent.model.CliDeviceCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CliDeviceCodeRepository extends JpaRepository<CliDeviceCode, Long> {
    List<CliDeviceCode> findAllByUserCodePrefix(String userCodePrefix);
}
