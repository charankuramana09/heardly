package com.otter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class WhisperClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public WhisperClient(@Value("${otterfree.whisper.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
    }

    @SuppressWarnings("unchecked")
    public TranscriptionResult transcribe(Path audioFile) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audioFile.toFile()));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        Map<String, Object> resp = restTemplate.postForObject(baseUrl + "/transcribe", request, Map.class);
        if (resp == null) {
            throw new IllegalStateException("Empty response from whisper service");
        }

        String language = (String) resp.get("language");
        Object durObj = resp.get("duration");
        Double duration = (durObj instanceof Number n) ? n.doubleValue() : null;
        String fullText = (String) resp.get("full_text");
        List<Map<String, Object>> segments = (List<Map<String, Object>>) resp.get("segments");
        return new TranscriptionResult(language, duration, fullText, segments);
    }

    public record TranscriptionResult(String language, Double durationSeconds, String fullText, List<Map<String, Object>> segments) {}
}
