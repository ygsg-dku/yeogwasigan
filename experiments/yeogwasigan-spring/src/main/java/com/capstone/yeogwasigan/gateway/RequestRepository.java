package com.capstone.yeogwasigan.gateway;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RequestRepository extends JpaRepository<RequestEntity, String> {
    @Query("select coalesce(max(r.seqNo), 0) from RequestEntity r")
    int maxSeqNo();
}