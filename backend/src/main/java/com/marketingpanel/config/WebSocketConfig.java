package com.marketingpanel.config;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.auth.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live UI over STOMP: /topic/ws/{workspaceId}/campaign/{id}. CONNECT carries the JWT; subscriptions are limited to the caller's workspace. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final Pattern TOPIC = Pattern.compile("^/topic/ws/(\\d+)/.+$");
    private final JwtService jwt;
    private final Settings settings;

    public WebSocketConfig(JwtService jwt, Settings settings) {
        this.jwt = jwt;
        this.settings = settings;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(settings.corsOrigins.toArray(new String[0]));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc == null) return message;
                if (StompCommand.CONNECT.equals(acc.getCommand())) {
                    String h = acc.getFirstNativeHeader("Authorization");
                    if (h == null || !h.startsWith("Bearer ")) throw new IllegalArgumentException("Unauthorized");
                    AuthUser u = jwt.parse(h.substring(7));
                    acc.setUser(new WsPrincipal(u));
                } else if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
                    Principal p = acc.getUser();
                    String dest = acc.getDestination();
                    Matcher m = dest == null ? null : TOPIC.matcher(dest);
                    if (!(p instanceof WsPrincipal wp) || m == null || !m.matches()
                            || wp.user.workspaceId() == null || wp.user.workspaceId() != Long.parseLong(m.group(1)))
                        throw new IllegalArgumentException("Forbidden");
                }
                return message;
            }
        });
    }

    record WsPrincipal(AuthUser user) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(user.userId());
        }
    }
}
