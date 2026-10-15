package com.marketingpanel.affiliate;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class OfferController {
    private final OfferService offers;
    private final ConversionService conversions;

    public OfferController(OfferService offers, ConversionService conversions) {
        this.offers = offers;
        this.conversions = conversions;
    }

    @GetMapping("/offers")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> list() {
        return offers.list(Auth.ws());
    }

    @GetMapping("/offers/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> get(@PathVariable long id) {
        return offers.get(Auth.ws(), id);
    }

    @PostMapping("/offers")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody OfferService.OfferRequest r) {
        return offers.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/offers/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @RequestBody OfferService.OfferRequest r) {
        return offers.update(Auth.ws(), id, r);
    }

    public record Status(String status) {}

    @PatchMapping("/offers/{id}/status")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> status(@PathVariable long id, @RequestBody Status s) {
        return offers.setStatus(Auth.ws(), id, s.status());
    }

    @PostMapping("/offers/{id}/test")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> test(@PathVariable long id) {
        return offers.test(Auth.ws(), id);
    }

    @PostMapping("/offers/{id}/rotate-secret")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> rotate(@PathVariable long id) {
        return offers.rotateSecret(Auth.ws(), id);
    }

    @GetMapping("/conversions")
    @PreAuthorize(Roles.MANAGER_OR_SUPER)
    public Map<String, Object> conversions(@RequestParam(required = false) Long offerId, @RequestParam(required = false) String status, @RequestParam(required = false) String promoter,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "50") int size) {
        return conversions.list(Auth.user(), offerId, status, promoter, from, to, cursor, size);
    }

    @GetMapping("/conversions/logs")
    @PreAuthorize(Roles.MANAGER_OR_SUPER)
    public List<Map<String, Object>> logs(@RequestParam(required = false) Long offerId, @RequestParam(required = false) String result,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return conversions.logs(Auth.user(), offerId, result, from, to);
    }

    @PatchMapping("/conversions/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','AGENCY_ADMIN','SUPER_ADMIN','FINANCE')")
    public Map<String, Object> act(@PathVariable long id, @RequestBody ConversionService.Action a) {
        return conversions.act(Auth.user(), id, a);
    }

    @PatchMapping("/conversions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','AGENCY_ADMIN','SUPER_ADMIN','FINANCE')")
    public Map<String, Object> bulk(@RequestBody ConversionService.Bulk b) {
        return conversions.bulk(Auth.user(), b);
    }
}
