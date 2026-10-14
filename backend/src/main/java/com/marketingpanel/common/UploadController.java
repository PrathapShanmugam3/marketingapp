package com.marketingpanel.common;

import com.marketingpanel.auth.Roles;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** VR-10 image rules (signature checked, size, minimum dimension, EXIF stripped) plus PDF/MP4 for templates and tickets. */
@RestController
public class UploadController {
    private final FileStore store;

    public UploadController(FileStore store) {
        this.store = store;
    }

    private record Rule(long maxBytes, int minShort, boolean squareOnly, double minRatio, double maxRatio, boolean allowPdf, boolean allowMp4) {}

    private static Rule rule(String kind) {
        return switch (kind) {
            case "logo" -> new Rule(2L << 20, 0, false, 0, 100, false, false);
            case "social" -> new Rule(8L << 20, 320, false, 0.8, 1.91, false, true);
            case "appicon" -> new Rule(5L << 20, 512, true, 0, 100, false, false);
            case "ticket" -> new Rule(5L << 20, 0, false, 0, 100, true, false);
            case "header" -> new Rule(100L << 20, 320, false, 0, 100, true, true);
            case "landing" -> new Rule(5L << 20, 320, false, 0, 100, false, false);
            default -> new Rule(5L << 20, 320, false, 0, 100, false, false);
        };
    }

    @PostMapping("/api/v1/uploads")
    @PreAuthorize(Roles.AUTHENTICATED)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "image") String kind) throws IOException {
        if (file.isEmpty()) throw ApiException.invalid("file", "upload.empty", "Upload a JPG or PNG under 5 MB.");
        byte[] data = file.getBytes();
        Rule r = rule(kind);
        String msg = kind.equals("logo") ? "Upload a JPG or PNG under 2 MB." : "Upload a JPG or PNG under 5 MB.";
        if (isPdf(data)) {
            if (!r.allowPdf || data.length > (kind.equals("header") ? 100L << 20 : r.maxBytes))
                throw ApiException.invalid("file", "upload.type", "This file type is not supported.");
            return result(store.save(data, "pdf"), "application/pdf", data.length);
        }
        if (isMp4(data)) {
            long limit = kind.equals("header") ? 16L << 20 : 100L << 20;
            if (!r.allowMp4 || data.length > limit) throw ApiException.invalid("file", "upload.type", "This file type is not supported.");
            return result(store.save(data, "mp4"), "video/mp4", data.length);
        }
        String ext = isJpeg(data) ? "jpg" : isPng(data) ? "png" : null;
        if (ext == null) throw ApiException.invalid("file", "upload.type", msg);
        if (data.length > r.maxBytes) throw ApiException.invalid("file", "upload.size", msg);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw ApiException.invalid("file", "upload.type", msg);
        int w = img.getWidth(), h = img.getHeight();
        if (Math.min(w, h) < r.minShort) throw ApiException.invalid("file", "upload.small", "Image must be at least " + r.minShort + " px on the short side.");
        if (r.squareOnly && w != h) throw ApiException.invalid("file", "upload.square", "Upload a square image.");
        double ratio = (double) w / h;
        if (ratio < r.minRatio || ratio > r.maxRatio) throw ApiException.invalid("file", "upload.ratio", "Image ratio must be between 4:5 and 1.91:1.");
        // Re-encode to strip EXIF/metadata.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage clean = img;
        if (ext.equals("jpg") && img.getType() != BufferedImage.TYPE_INT_RGB) {
            clean = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            clean.getGraphics().drawImage(img, 0, 0, java.awt.Color.WHITE, null);
        }
        ImageIO.write(clean, ext.equals("jpg") ? "jpg" : "png", out);
        byte[] bytes = out.toByteArray();
        return result(store.save(bytes, ext), ext.equals("jpg") ? "image/jpeg" : "image/png", bytes.length);
    }

    private static Map<String, Object> result(String url, String type, long size) {
        return Map.of("url", url, "contentType", type, "size", size);
    }

    static boolean isJpeg(byte[] d) {
        return d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF;
    }

    static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    static boolean isPdf(byte[] d) {
        return d.length > 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F';
    }

    static boolean isMp4(byte[] d) {
        return d.length > 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    @GetMapping("/files/**")
    public ResponseEntity<byte[]> serve(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse res) throws IOException {
        String rel = req.getRequestURI().substring(req.getRequestURI().indexOf("/files/") + 7);
        Path p = store.resolve(rel);
        if (p == null) return ResponseEntity.notFound().build();
        String name = p.getFileName().toString();
        MediaType mt = name.endsWith(".png") ? MediaType.IMAGE_PNG : name.endsWith(".jpg") ? MediaType.IMAGE_JPEG
                : name.endsWith(".pdf") ? MediaType.APPLICATION_PDF : name.endsWith(".mp4") ? MediaType.parseMediaType("video/mp4")
                : name.endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(mt).cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(Files.readAllBytes(p));
    }
}
