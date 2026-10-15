package com.marketingpanel.commerce;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CommerceController {
    private final CatalogService catalog;
    private final CouponService coupons;
    private final LoyaltyService loyalty;

    public CommerceController(CatalogService catalog, CouponService coupons, LoyaltyService loyalty) {
        this.catalog = catalog;
        this.coupons = coupons;
        this.loyalty = loyalty;
    }

    // ---- catalog
    @GetMapping("/api/v1/catalog/products")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> products(@RequestParam(required = false) String q) {
        return catalog.list(Auth.ws(), q);
    }

    @PostMapping("/api/v1/catalog/products")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> createProduct(@RequestBody CatalogService.ProductRequest r) {
        return catalog.create(Auth.ws(), r);
    }

    @PutMapping("/api/v1/catalog/products/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> updateProduct(@PathVariable long id, @RequestBody CatalogService.ProductRequest r) {
        return catalog.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/api/v1/catalog/products/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> deleteProduct(@PathVariable long id) {
        catalog.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    public record Share(long contactId) {}

    @PostMapping("/api/v1/catalog/products/{id}/share")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> share(@PathVariable long id, @RequestBody Share s) {
        return catalog.share(Auth.ws(), Auth.userId(), id, s.contactId());
    }

    @GetMapping(value = "/c/{ws}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> publicCatalog(@PathVariable long ws) {
        return ResponseEntity.ok(catalog.page(ws));
    }

    // ---- coupons
    @GetMapping("/api/v1/coupons")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> coupons() {
        return Map.of("items", coupons.list(Auth.ws()), "stats", coupons.stats(Auth.ws()));
    }

    @PostMapping("/api/v1/coupons")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> createCoupon(@RequestBody CouponService.Request r) {
        return coupons.create(Auth.ws(), r);
    }

    @PutMapping("/api/v1/coupons/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> updateCoupon(@PathVariable long id, @RequestBody CouponService.Request r) {
        return coupons.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/api/v1/coupons/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> deleteCoupon(@PathVariable long id) {
        coupons.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/coupons/validate")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> validate(@RequestBody CouponService.Redeem r) {
        return coupons.validate(Auth.ws(), r);
    }

    @PostMapping("/api/v1/coupons/redeem")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> redeem(@RequestBody CouponService.Redeem r) {
        return coupons.redeem(Auth.ws(), r);
    }

    // ---- loyalty
    @GetMapping("/api/v1/loyalty/settings")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> settings() {
        return loyalty.settings(Auth.ws());
    }

    @PutMapping("/api/v1/loyalty/settings")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> saveSettings(@RequestBody LoyaltyService.Settings s) {
        return loyalty.saveSettings(Auth.ws(), s);
    }

    @GetMapping("/api/v1/loyalty/members")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> members() {
        return loyalty.leaderboard(Auth.ws());
    }

    @GetMapping("/api/v1/loyalty/contacts/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> account(@PathVariable long id) {
        return loyalty.get(Auth.ws(), id);
    }

    @PostMapping("/api/v1/loyalty/earn")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> earn(@RequestBody LoyaltyService.Earn e) {
        return loyalty.earn(Auth.ws(), e);
    }

    @PostMapping("/api/v1/loyalty/redeem")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> redeemPoints(@RequestBody LoyaltyService.Spend s) {
        return loyalty.redeem(Auth.ws(), s);
    }

    @PostMapping("/api/v1/loyalty/adjust")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> adjust(@RequestBody LoyaltyService.Adjust a) {
        return loyalty.adjust(Auth.ws(), a);
    }

    @PostMapping("/api/v1/loyalty/refer")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> refer(@RequestBody LoyaltyService.Refer r) {
        return loyalty.refer(Auth.ws(), r);
    }
}
