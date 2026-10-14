package com.marketingpanel.common;

/** Domain events used to decouple modules (e.g. automations listening to CRM changes). */
public final class Events {
    private Events() {}

    public record ContactCreated(long workspaceId, long contactId) {}

    public record TagAdded(long workspaceId, long contactId, long tagId) {}

    public record FormSubmitted(long workspaceId, long contactId, long formId) {}

    public record InboundMessage(long workspaceId, long contactId, String text) {}

    public record LeadStatusChanged(long workspaceId, long contactId, long leadId, String status) {}

    public record LinkClicked(long workspaceId, long contactId, long linkId) {}

    public record OptedOut(long workspaceId, long contactId) {}
}
