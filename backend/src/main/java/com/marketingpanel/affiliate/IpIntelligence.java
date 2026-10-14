package com.marketingpanel.affiliate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** VPN / proxy lookup hook (SRS P32 fraud rules). The default adapter reports nothing; plug an IP-intelligence provider in here. */
public interface IpIntelligence {
    boolean isVpn(String ip);

    @Component
    @ConditionalOnMissingBean(value = IpIntelligence.class, ignored = NoOp.class)
    class NoOp implements IpIntelligence {
        @Override
        public boolean isVpn(String ip) {
            return false;
        }
    }
}
