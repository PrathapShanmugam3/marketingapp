package com.marketingpanel.whatsapp;

import java.util.List;

public record TemplateSpec(String name, String language, String category, String headerType, String headerText, String headerMediaUrl,
                           String body, String footer, List<java.util.Map<String, Object>> buttons, List<String> samples) {}
