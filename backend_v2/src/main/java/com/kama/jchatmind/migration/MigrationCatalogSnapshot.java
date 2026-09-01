package com.kama.jchatmind.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

public record MigrationCatalogSnapshot(
        Set<MigrationCatalogContract.CatalogObject> objects
) {

    public MigrationCatalogSnapshot {
        objects = Set.copyOf(objects);
    }

    public String fingerprint() {
        String canonical = new TreeSet<>(objects.stream()
                .map(MigrationCatalogContract.CatalogObject::canonicalName)
                .toList()).stream().reduce((left, right) -> left + "\n" + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint migration catalog", e);
        }
    }
}
