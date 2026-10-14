package com.marketingpanel.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final MessageSource messageSource;

    public WebConfig(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public Validator getValidator() {
        LocalValidatorFactoryBean v = new LocalValidatorFactoryBean();
        v.setValidationMessageSource(messageSource);
        return v;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver r = new AcceptHeaderLocaleResolver();
        r.setSupportedLocales(List.of(Locale.ENGLISH, new Locale("ta")));
        r.setDefaultLocale(Locale.ENGLISH);
        return r;
    }

    /** All DB timestamps are UTC: serialise LocalDateTime as an ISO instant ending in Z. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcDates() {
        return b -> {
            SimpleModule m = new SimpleModule();
            m.addSerializer(LocalDateTime.class, new StdSerializer<>(LocalDateTime.class) {
                @Override
                public void serialize(LocalDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
                    g.writeString(v.toString() + (v.getSecond() == 0 && v.getNano() == 0 && v.toString().length() == 16 ? ":00" : "") + "Z");
                }
            });
            b.modulesToInstall(m);
        };
    }
}
