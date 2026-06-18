package com.otter.service;

import java.util.UUID;

public record TranscriptionCompletedEvent(UUID recordingId) {}
