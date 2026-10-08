package com.foremen.service.document.pdf;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

/**
 * FOR-05-08 (Requirements 1.5, 3.6): a minimal in-memory {@link MultipartFile} over a byte array,
 * used by {@link com.foremen.service.document.PdfFreezeService} to hand the freshly rendered PDF to
 * the shared {@link com.foremen.service.image.ImageStorage} seam — which is defined in terms of
 * {@code MultipartFile} (the same seam {@code DocumentMediaService} uses). The server generates the
 * frozen artifact, so there is no HTTP upload to wrap; this adapter bridges that gap without
 * widening the storage seam.
 */
public final class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        try (OutputStream out = Files.newOutputStream(dest)) {
            out.write(content);
        }
    }

    @Override
    public void transferTo(File dest) throws IOException {
        transferTo(dest.toPath());
    }
}
