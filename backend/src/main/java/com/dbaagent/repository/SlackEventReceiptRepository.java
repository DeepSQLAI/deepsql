package com.dbaagent.repository;

import com.dbaagent.model.SlackEventReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackEventReceiptRepository extends JpaRepository<SlackEventReceipt, String> {
}
