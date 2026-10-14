package com.marketingpanel.growth;

import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/** Live DNS checks for custom domains (TXT ownership proof + CNAME or A record). */
@Component
public class DnsVerifier {
    private final Settings settings;

    public DnsVerifier(Settings settings) {
        this.settings = settings;
    }

    public record Result(boolean ok, String missing) {}

    private List<String> lookup(String name, String type) {
        List<String> out = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(name, new String[]{type});
            Attribute a = attrs.get(type);
            if (a != null) {
                NamingEnumeration<?> e = a.getAll();
                while (e.hasMore()) out.add(String.valueOf(e.next()).replace("\"", "").trim().replaceAll("\\.$", ""));
            }
            ctx.close();
        } catch (Exception ignored) {
            // missing record or resolver problem: treated as "not found"
        }
        return out;
    }

    /** Step 1: TXT _mp-verify.&lt;domain&gt; = token.  Step 2: CNAME to connect.&lt;platform&gt; (subdomain) or A record to the server IP (root domain). */
    public Result verify(String domain, String token, boolean apex) {
        if (settings.skipDns) return new Result(true, null);
        String txtHost = "_mp-verify." + domain;
        if (lookup(txtHost, "TXT").stream().noneMatch(v -> v.contains(token)))
            return new Result(false, "Add a TXT record: " + txtHost + " = " + token);
        if (apex) {
            if (lookup(domain, "A").stream().noneMatch(v -> v.equals(settings.serverIp)))
                return new Result(false, "Add an A record: " + domain + " -> " + settings.serverIp);
        } else if (lookup(domain, "CNAME").stream().noneMatch(v -> v.equalsIgnoreCase(settings.cnameTarget))) {
            return new Result(false, "Add a CNAME record: " + domain + " -> " + settings.cnameTarget);
        }
        return new Result(true, null);
    }
}
