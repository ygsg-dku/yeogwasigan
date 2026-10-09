package com.capstone.yeogwasigan.gateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryRequestStore implements RequestStore {
    private final Map<String, GatewayRequest> requests = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Override public int nextSeq() { return seq.incrementAndGet(); }
    @Override public void save(GatewayRequest r) { requests.put(r.getId(), r); }
    @Override public Optional<GatewayRequest> find(String id) { return Optional.ofNullable(requests.get(id)); }
    @Override public List<GatewayRequest> findAll() { return List.copyOf(requests.values()); }
}