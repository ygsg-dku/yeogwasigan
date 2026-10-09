package com.capstone.yeogwasigan.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class JpaRequestStoreTest {

    @Autowired RequestRepository requests;
    @Autowired AuditEventRepository audits;

    @Test
    void 저장한_요청은_새_저장소로_읽어도_같다() {
        JpaRequestStore store1 = new JpaRequestStore(requests, audits);
        GatewayRequest r = new GatewayRequest("REQ-0001", "alice", "incident_analysis", "openai", "abc", 3, "v1",
                List.of(Map.of("body", "x")), "pkg", 10, 5, List.of("a"),
                List.of(new GatewayRequest.Warning("EMAIL", 1)));
        r.log("alice", "CREATED", "테스트");
        store1.save(r);
        r.approve("bob", "fp");
        r.log("bob", "APPROVED", "fp");
        store1.save(r);

        JpaRequestStore store2 = new JpaRequestStore(requests, audits);   // 재시작한 것처럼 새 저장소
        GatewayRequest back = store2.find("REQ-0001").orElseThrow();

        assertEquals(GatewayRequest.Status.APPROVED, back.getStatus());
        assertEquals("pkg", back.getPackageSha256());
        assertEquals(2, back.getAudit().size());
        assertEquals(1, back.getWarnings().get(0).count());
        assertEquals(2, store2.nextSeq());   // 번호가 이어진다
    }
}