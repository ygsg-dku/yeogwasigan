package com.capstone.yeogwasigan.gateway;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayRequestRepository extends JpaRepository<GatewayRequest, String> {

    List<GatewayRequest> findAllByOrderByIdDesc();

    List<GatewayRequest> findByStatusOrderByIdDesc(GatewayRequest.Status status);

    List<GatewayRequest> findByPayloadExpiresAtBeforeAndPayloadJsonIsNotNull(Instant now);
}
