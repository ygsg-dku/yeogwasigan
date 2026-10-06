package com.capstone.yeogwasigan.gateway;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 추가와 조회만 있다. 수정·삭제 메서드를 일부러 두지 않았다(CrudRepository 를 쓰지 않는 이유). */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByRequestIdOrderByIdAsc(String requestId);
}
