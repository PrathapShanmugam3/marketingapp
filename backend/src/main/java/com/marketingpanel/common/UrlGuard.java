package com.marketingpanel.common;

import java.net.InetAddress;
import java.net.URI;

/** SSRF guard (SRS 4.6) and URL rules (VR-08). */
public final class UrlGuard {
    private UrlGuard() {}

    /** https only, no IP literal, no localhost / private ranges. */
    public static boolean isPublicHttps(String url, int maxLen) {
        if (url == null || url.length() > maxLen) return false;
        try {
            URI u = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            String host = u.getHost();
            if (host == null || host.isBlank()) return false;
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false;
            if (host.matches("^[0-9.]+$") || host.contains(":")) return false; // IP literals
            return host.contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolves the host and refuses private, loopback, link-local and multicast addresses. */
    public static void assertSafeToFetch(String url) {
        if (!isPublicHttps(url, 2000)) throw ApiException.badRequest("This link cannot be used.");
        try {
            String host = URI.create(url).getHost();
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                        || a.isMulticastAddress()) {
                    throw ApiException.badRequest("This link cannot be used.");
                }
                byte[] b = a.getAddress();
                if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40) {
                    throw ApiException.badRequest("This link cannot be used."); // CGNAT 100.64/10
                }
                if (b.length == 16 && (b[0] & 0xfe) == 0xfc) {
                    throw ApiException.badRequest("This link cannot be used."); // fc00::/7
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("This link cannot be used.");
        }
    }

    public static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
