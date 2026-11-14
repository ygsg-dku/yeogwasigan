package com.capstone.yeogwasigan.web;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.yeogwasigan.gateway.ProposalService;
import com.capstone.yeogwasigan.gateway.ProposalService.Proposal;

/**
 * 목록 제안 API (proposals.html). 로그인한 게이트웨이 사용자만.
 *
 * <pre>
 * POST /api/proposals                    알고리즘 실행 시작 → 제안 하나 (RUNNING)
 * GET  /api/proposals                    제안 목록 (최신 먼저)
 * GET  /api/proposals/{id}?since=n       상태 + n번째 이후 진행 기록
 * POST /api/proposals/{id}/decision      {approve, comment} 승인하면 게이트웨이 목록에 등록
 * </pre>
 */
@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    public record DecisionBody(boolean approve, String comment) {
    }

    private final ProposalService proposals;

    public ProposalController(ProposalService proposals) {
        this.proposals = proposals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(Principal principal) {
        return summary(proposals.start(principal.getName()));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return proposals.list().stream().map(ProposalController::summary).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id, @RequestParam(defaultValue = "0") int since) {
        Proposal p = proposals.get(id);
        Map<String, Object> m = summary(p);
        m.put("events", p.events.subList(Math.min(Math.max(since, 0), p.events.size()), p.events.size()));
        m.put("comment", p.comment);
        m.put("error", p.error);
        return m;
    }

    @PostMapping("/{id}/decision")
    public Map<String, Object> decide(Principal principal, @PathVariable String id, @RequestBody DecisionBody body) {
        return summary(proposals.decide(id, principal.getName(), body.approve(), body.comment()));
    }

    private static Map<String, Object> summary(Proposal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("status", p.status);
        m.put("requester", p.requester);
        m.put("approver", p.approver);
        m.put("createdAt", p.createdAt);
        m.put("mode", p.mode);
        m.put("fields", p.fields);
        m.put("purposeId", p.purposeId);
        m.put("displayName", p.displayName);
        return m;
    }
}
