package com.marketingpanel.jobs;

import com.fasterxml.jackson.databind.JsonNode;

public interface JobHandler {
    String type();

    void handle(JsonNode payload) throws Exception;

    /** Called once when a job exhausts its attempts and becomes DEAD. */
    default void onDead(JsonNode payload, String error) {}
}
