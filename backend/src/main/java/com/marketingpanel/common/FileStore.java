package com.marketingpanel.common;

import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Local-disk object store; swap for S3/R2 by replacing this bean (URLs stay /files/... or absolute). */
@Component
public class FileStore {
    private final Path root;
    private final Settings settings;

    public FileStore(Settings settings) {
        this.settings = settings;
        this.root = Path.of(settings.storageDir).toAbsolutePath().normalize();
    }

    public String save(byte[] data, String ext) {
        try {
            LocalDate d = LocalDate.now();
            String rel = d.getYear() + "/" + String.format("%02d", d.getMonthValue()) + "/" + Ids.ulid().toLowerCase() + "." + ext;
            Path p = root.resolve(rel).normalize();
            Files.createDirectories(p.getParent());
            Files.write(p, data);
            return settings.publicBaseUrl + "/files/" + rel;
        } catch (IOException e) {
            throw new IllegalStateException("Could not store file", e);
        }
    }

    /** Resolves a public relative path safely inside the root, or null. */
    public Path resolve(String rel) {
        Path p = root.resolve(rel).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    public byte[] read(String publicUrl) {
        String marker = "/files/";
        int i = publicUrl.indexOf(marker);
        if (i < 0) return null;
        Path p = resolve(publicUrl.substring(i + marker.length()));
        try {
            return p == null ? null : Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    public Path root() {
        return root;
    }
}
