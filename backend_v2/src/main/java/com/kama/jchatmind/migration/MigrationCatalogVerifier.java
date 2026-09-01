package com.kama.jchatmind.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class MigrationCatalogVerifier {

    public record VerificationResult(
            boolean verified,
            List<String> missingObjects,
            List<String> forbiddenObjects,
            List<String> unexpectedObjects,
            List<String> definitionMismatches,
            String contractSha256,
            String observedFingerprint
    ) {

        public VerificationResult {
            missingObjects = List.copyOf(missingObjects);
            forbiddenObjects = List.copyOf(forbiddenObjects);
            unexpectedObjects = List.copyOf(unexpectedObjects);
            definitionMismatches = List.copyOf(definitionMismatches);
            boolean expectedVerification = missingObjects.isEmpty()
                    && forbiddenObjects.isEmpty()
                    && unexpectedObjects.isEmpty()
                    && definitionMismatches.isEmpty();
            if (verified != expectedVerification) {
                throw new IllegalArgumentException("Catalog verification result is inconsistent");
            }
        }

        public VerificationResult(
                boolean verified,
                List<String> missingObjects,
                List<String> forbiddenObjects,
                String contractSha256,
                String observedFingerprint
        ) {
            this(verified, missingObjects, forbiddenObjects, List.of(), List.of(), contractSha256, observedFingerprint);
        }
    }

    private final MigrationCatalogContract contract;

    public MigrationCatalogVerifier(MigrationCatalogContract contract) {
        this.contract = contract;
    }

    public VerificationResult verify(MigrationCatalogSnapshot snapshot) {
        Set<MigrationCatalogContract.CatalogObject> actual = snapshot.objects();
        List<String> missing = names(difference(contract.requiredObjects(), actual));
        List<String> forbidden = names(intersection(contract.forbiddenObjects(), actual));
        List<String> unexpected = names(intersection(
                checkedObjects(actual), subtract(actual, contract.allowedObjects())
        ));
        List<String> definitionMismatches = definitionMismatches(actual);
        return new VerificationResult(
                missing.isEmpty() && forbidden.isEmpty() && unexpected.isEmpty() && definitionMismatches.isEmpty(),
                missing,
                forbidden,
                unexpected,
                definitionMismatches,
                contract.sha256(),
                snapshot.fingerprint()
        );
    }

    private List<String> definitionMismatches(Set<MigrationCatalogContract.CatalogObject> actual) {
        List<String> mismatches = new ArrayList<>();
        for (MigrationCatalogContract.CatalogObject expected : contract.requiredDefinitionObjects()) {
            int separator = expected.qualifier().indexOf(':');
            String matchType = separator < 0 ? "exact" : expected.qualifier().substring(0, separator);
            String expectedValue = separator < 0 ? expected.qualifier() : expected.qualifier().substring(separator + 1);
            boolean matched = actual.stream().anyMatch(candidate ->
                    candidate.kind().equals(expected.kind())
                            && candidate.schema().equals(expected.schema())
                            && candidate.table().equals(expected.table())
                            && candidate.name().equals(expected.name())
                            && ("contains".equals(matchType)
                            ? candidate.qualifier().contains(expectedValue)
                            : candidate.qualifier().equals(expectedValue))
            );
            if (!matched) {
                mismatches.add(expected.canonicalName());
            }
        }
        return mismatches;
    }

    private Set<MigrationCatalogContract.CatalogObject> checkedObjects(
            Set<MigrationCatalogContract.CatalogObject> objects
    ) {
        Set<String> checkedKinds = Set.of(
                "extension", "table", "column", "constraint", "index", "function", "trigger"
        );
        Set<MigrationCatalogContract.CatalogObject> result = new TreeSet<>(
                java.util.Comparator.comparing(MigrationCatalogContract.CatalogObject::canonicalName)
        );
        for (MigrationCatalogContract.CatalogObject object : objects) {
            if (checkedKinds.contains(object.kind())) {
                result.add(object);
            }
        }
        return result;
    }

    private Set<MigrationCatalogContract.CatalogObject> difference(
            Set<MigrationCatalogContract.CatalogObject> expected,
            Set<MigrationCatalogContract.CatalogObject> actual
    ) {
        Set<MigrationCatalogContract.CatalogObject> result = new TreeSet<>(
                java.util.Comparator.comparing(MigrationCatalogContract.CatalogObject::canonicalName)
        );
        result.addAll(expected);
        result.removeAll(actual);
        return result;
    }

    private Set<MigrationCatalogContract.CatalogObject> intersection(
            Set<MigrationCatalogContract.CatalogObject> expected,
            Set<MigrationCatalogContract.CatalogObject> actual
    ) {
        Set<MigrationCatalogContract.CatalogObject> result = new TreeSet<>(
                java.util.Comparator.comparing(MigrationCatalogContract.CatalogObject::canonicalName)
        );
        result.addAll(expected);
        result.retainAll(actual);
        return result;
    }

    private Set<MigrationCatalogContract.CatalogObject> subtract(
            Set<MigrationCatalogContract.CatalogObject> left,
            Set<MigrationCatalogContract.CatalogObject> right
    ) {
        Set<MigrationCatalogContract.CatalogObject> result = new TreeSet<>(
                java.util.Comparator.comparing(MigrationCatalogContract.CatalogObject::canonicalName)
        );
        result.addAll(left);
        result.removeAll(right);
        return result;
    }

    private List<String> names(Set<MigrationCatalogContract.CatalogObject> objects) {
        List<String> names = new ArrayList<>();
        for (MigrationCatalogContract.CatalogObject object : objects) {
            names.add(object.canonicalName());
        }
        return names;
    }
}
