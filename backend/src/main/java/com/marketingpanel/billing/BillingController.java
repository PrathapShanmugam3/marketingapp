package com.marketingpanel.billing;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.payment.PaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing")
@PreAuthorize(Roles.OWNER)
public class BillingController {
    private final BillingService svc;
    private final InvoiceService invoices;
    private final PaymentService payments;

    public BillingController(BillingService svc, InvoiceService invoices, PaymentService payments) {
        this.svc = svc;
        this.invoices = invoices;
        this.payments = payments;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return svc.overview(Auth.ws());
    }

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody BillingService.Subscribe r) {
        return svc.subscribe(Auth.ws(), r);
    }

    @PostMapping("/topups")
    public Map<String, Object> topUp(@RequestBody BillingService.TopUp r) {
        return svc.topUp(Auth.ws(), r);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        svc.cancelAtPeriodEnd(Auth.ws());
        return Map.of("ok", true);
    }

    @GetMapping("/payments/{id}")
    public Map<String, Object> payment(@PathVariable long id) {
        return payments.status(Auth.ws(), id);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=invoice-" + id + ".pdf")
                .body(invoices.pdf(Auth.ws(), id));
    }
}
