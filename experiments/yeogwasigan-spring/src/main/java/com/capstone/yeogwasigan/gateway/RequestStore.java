package com.capstone.yeogwasigan.gateway;

import java.util.List;
import java.util.Optional;

/** 요청 저장소. 감사 기록은 요청과 함께 저장되며 추가만 된다. */
public interface RequestStore {
    int nextSeq();
    void save(GatewayRequest r);
    Optional<GatewayRequest> find(String id);
    List<GatewayRequest> findAll();
}