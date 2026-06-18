package com.otter.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path audioDir;

    public FileStorageService(@Value("${otterfree.storage.audio-dir}") String audioDir) {
        this.audioDir = Paths.get(audioDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(audioDir);
    }

    public StoredFile save(MultipartFile file, UUID recordingId) throws IOException {
        String originalName = file.getOriginalFilename();
        String suffix = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                suffix = originalName.substring(dot);
            }
        }
        Path target = audioDir.resolve(recordingId + suffix);
        try (var in = file.getInputStream()) {
            Files.copy(in, target);
        }
        return new StoredFile(target.toString(), file.getSize(), file.getContentType(), originalName);
    }

    public Path resolve(String absolutePath) {
        return Paths.get(absolutePath);
    }

    public record StoredFile(String absolutePath, long sizeBytes, String contentType, String originalFilename) {}
}
