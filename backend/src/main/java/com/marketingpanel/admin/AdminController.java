package com.marketingpanel.admin;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.payout.FinanceService;
import com.marketingpanel.payout.LedgerGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final FinanceService finance;
    private final LedgerGuard guard;

    public AdminController(AdminService admin, FinanceService finance, LedgerGuard guard) {
        this.admin = admin;
        this.finance = finance;
        this.guard = guard;
    }

    @GetMapping("/stats")
    @PreAuthorize(Roles.ADMIN)
    public Map<String, Object> stats() {
        return admin.stats();
    }

    @GetMapping("/workspaces")
    @PreAuthorize(Roles.ADMIN)
    public Map<String, Object> workspaces(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) Long cursor) {
        return admin.workspaces(q, status, cursor);
    }

    public record StatusChange(String status, String reason) {}

    @PatchMapping("/workspaces/{id}")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> status(@PathVariable long id, @RequestBody StatusChange r) {
        return admin.setStatus(Auth.user(), id, r.status(), r.reason());
    }

    public record Adjustment(BigDecimal amount, String note) {}

    @PostMapping("/workspaces/{id}/wallet-adjustments")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> adjust(@PathVariable long id, @RequestBody Adjustment r) {
        return admin.adjustWallet(Auth.user(), id, r.amount(), r.note());
    }

    @GetMapping("/message-rates")
    @PreAuthorize(Roles.ADMIN)
    public List<Map<String, Object>> rates() {
        return admin.rates();
    }

    @PostMapping("/message-rates")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> addRate(@RequestBody AdminService.RateRequest r) {
        return admin.addRate(Auth.user(), r);
    }

    @GetMapping("/plans")
    @PreAuthorize(Roles.ADMIN)
    public List<Map<String, Object>> plans() {
        return admin.plans();
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> plan(@PathVariable long id, @RequestBody AdminService.PlanRequest r) {
        return admin.updatePlan(Auth.user(), id, r);
    }

    @GetMapping("/audit-logs")
    @PreAuthorize(Roles.ADMIN)
    public List<Map<String, Object>> audit(@RequestParam(required = false) Long workspaceId, @RequestParam(required = false) String action, @RequestParam(required = false) Long cursor) {
        return admin.auditLogs(workspaceId, action, cursor);
    }

    @GetMapping("/jobs/dead")
    @PreAuthorize(Roles.SUPER)
    public List<Map<String, Object>> dead() {
        return admin.deadJobs();
    }

    @PostMapping("/jobs/{id}/retry")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> retry(@PathVariable long id) {
        admin.retryJob(id);
        return Map.of("ok", true);
    }

    // ---- Finance (P38)

    @GetMapping("/payouts")
    @PreAuthorize(Roles.FINANCE)
    public List<Map<String, Object>> payouts(@RequestParam(required = false) String status) {
        return finance.queue(status);
    }

    @GetMapping("/payouts/batches")
    @PreAuthorize(Roles.FINANCE)
    public List<Map<String, Object>> batches(@RequestParam(required = false) String status) {
        return finance.queue(status);
    }

    @PostMapping("/payouts/batches")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> batch(@RequestBody FinanceService.BatchRequest r) {
        return finance.batch(Auth.user(), r);
    }

    @GetMapping("/promoters")
    @PreAuthorize(Roles.FINANCE)
    public List<Map<String, Object>> promoters(@RequestParam(required = false) String q) {
        return finance.promoters(q);
    }

    @PatchMapping("/promoters/{id}")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> promoter(@PathVariable long id, @RequestBody StatusChange r) {
        return finance.setPromoterStatus(Auth.user(), id, r.status(), r.reason());
    }

    @GetMapping("/fraud")
    @PreAuthorize(Roles.FINANCE)
    public List<Map<String, Object>> fraud() {
        return finance.fraud();
    }

    @GetMapping("/reconciliation")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> settlements() {
        return Map.of("settlements", finance.settlements(), "heldPayments", finance.heldPayments(), "ledgerBlocked", guard.blocked());
    }

    public record RunRequest(String day) {}

    @PostMapping("/reconciliation/run")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> run(@RequestBody(required = false) RunRequest r) {
        LocalDate day = r == null || r.day() == null ? LocalDate.now().minusDays(1) : LocalDate.parse(r.day());
        return finance.reconcile(day, Auth.userId());
    }

    @PostMapping("/reconciliation/import")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return finance.importSettlement(file.getBytes(), Auth.userId());
    }

    @PostMapping("/ledger/check")
    @PreAuthorize(Roles.FINANCE)
    public Map<String, Object> ledger() {
        List<String> diffs = guard.check();
        return Map.of("consistent", diffs.isEmpty(), "differences", diffs);
    }

    @PostMapping("/ledger/unblock")
    @PreAuthorize(Roles.SUPER)
    public Map<String, Object> unblock() {
        guard.clear(Auth.userId());
        return Map.of("ok", true);
    }
}
