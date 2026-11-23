package io.github.omerbar4.paymentledger.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/** Resolves a configured PEM: raw text, {@code classpath:}, or {@code file:}. */
public final class PemSources {

    private PemSources() {
    }

    public static String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("classpath:")) {
                return StreamUtils.copyToString(new ClassPathResource(trimmed.substring("classpath:".length())).getInputStream(),
                        StandardCharsets.UTF_8);
            }
            if (trimmed.startsWith("file:")) {
                String path = trimmed.substring("file:".length());
                return Files.readString(Path.of(path));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read attestation PEM", ex);
        }
        if (trimmed.contains("\\n") && !trimmed.contains("\n")) {
            return trimmed.replace("\\n", "\n");
        }
        return trimmed;
    }
}
