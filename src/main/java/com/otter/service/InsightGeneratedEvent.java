package com.otter.service;

import java.util.UUID;

/** Published when an AI insight has been successfully generated for a recording. */
public record InsightGeneratedEvent(UUID recordingId) {
}
