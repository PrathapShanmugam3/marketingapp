package com.marketingpanel.auth;

import java.net.InetAddress;

/** Matches an IP against a plain address or an IPv4/IPv6 CIDR entry. */
public final class IpMatcher {
    private IpMatcher() {}

    public static boolean matches(String entry, String ip) {
        if (entry == null || ip == null) return false;
        try {
            if (!entry.contains("/")) return InetAddress.getByName(entry).equals(InetAddress.getByName(ip));
            String[] p = entry.split("/");
            byte[] net = InetAddress.getByName(p[0]).getAddress();
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (net.length != addr.length) return false;
            int bits = Integer.parseInt(p[1]);
            for (int i = 0; i < bits; i++) {
                int mask = 0x80 >> (i % 8);
                if ((net[i / 8] & mask) != (addr[i / 8] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean valid(String entry) {
        try {
            String host = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry;
            if (!host.matches("^[0-9a-fA-F:.]+$")) return false;
            InetAddress.getByName(host);
            if (entry.contains("/")) {
                int bits = Integer.parseInt(entry.substring(entry.indexOf('/') + 1));
                return bits >= 0 && bits <= (host.contains(":") ? 128 : 32);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
