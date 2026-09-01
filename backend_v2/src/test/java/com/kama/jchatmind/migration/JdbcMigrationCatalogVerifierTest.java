package com.kama.jchatmind.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMigrationCatalogVerifierTest {

    private static final Path CONTRACT = Path.of("..", "sql", "migrations", "catalog-contract.json");

    @Test
    void shouldExposeCatalogVerificationEntryPointForRelease() {
        Class<?> verifierType = load("com.kama.jchatmind.migration.JdbcMigrationCatalogVerifier");

        assertThat(verifierType).as("迁移发布必须提供 JDBC catalog 对账入口").isNotNull();
    }

    @Test
    void shouldAcceptCompleteCatalogAndRejectMissingOrForbiddenObjects() {
        MigrationCatalogContract contract = MigrationCatalogContract.load(CONTRACT);
        MigrationCatalogVerifier verifier = new MigrationCatalogVerifier(contract);

        HashSet<MigrationCatalogContract.CatalogObject> completeObjects = completeObjects(contract);
        MigrationCatalogVerifier.VerificationResult complete = verifier.verify(
                new MigrationCatalogSnapshot(completeObjects)
        );
        assertThat(complete.verified()).isTrue();
        assertThat(complete.missingObjects()).isEmpty();
        assertThat(complete.forbiddenObjects()).isEmpty();

        HashSet<MigrationCatalogContract.CatalogObject> missingObjects = new HashSet<>(contract.requiredObjects());
        MigrationCatalogContract.CatalogObject missing = contract.requiredObjects().stream()
                .filter(object -> "table".equals(object.kind()))
                .findFirst()
                .orElseThrow();
        missingObjects.remove(missing);
        MigrationCatalogVerifier.VerificationResult missingResult = verifier.verify(
                new MigrationCatalogSnapshot(missingObjects)
        );
        assertThat(missingResult.verified()).isFalse();
        assertThat(missingResult.missingObjects()).contains(missing.canonicalName());

        HashSet<MigrationCatalogContract.CatalogObject> forbiddenObjects = new HashSet<>(contract.requiredObjects());
        forbiddenObjects.addAll(contract.forbiddenObjects());
        MigrationCatalogVerifier.VerificationResult forbiddenResult = verifier.verify(
                new MigrationCatalogSnapshot(forbiddenObjects)
        );
        assertThat(forbiddenResult.verified()).isFalse();
        assertThat(forbiddenResult.forbiddenObjects())
                .containsExactlyInAnyOrderElementsOf(contract.forbiddenObjects().stream()
                        .map(MigrationCatalogContract.CatalogObject::canonicalName)
                .toList());
    }

    @Test
    void shouldRejectUnexpectedManagedCatalogObjects() {
        MigrationCatalogContract contract = MigrationCatalogContract.load(CONTRACT);
        MigrationCatalogVerifier verifier = new MigrationCatalogVerifier(contract);
        HashSet<MigrationCatalogContract.CatalogObject> objects = completeObjects(contract);
        objects.add(new MigrationCatalogContract.CatalogObject(
                "table", "public", "", "unexpected_release_drift", ""
        ));

        MigrationCatalogVerifier.VerificationResult result = verifier.verify(new MigrationCatalogSnapshot(objects));

        assertThat(result.verified()).isFalse();
        assertThat(result.unexpectedObjects()).contains("table:public::unexpected_release_drift:");
    }

    @Test
    void shouldRejectDefinitionDriftEvenWhenObjectNamesRemainPresent() {
        MigrationCatalogContract contract = MigrationCatalogContract.load(CONTRACT);
        MigrationCatalogVerifier verifier = new MigrationCatalogVerifier(contract);
        HashSet<MigrationCatalogContract.CatalogObject> objects = completeObjects(contract);
        MigrationCatalogContract.CatalogObject expected = contract.requiredDefinitionObjects().stream()
                .findFirst()
                .orElseThrow();
        objects.removeIf(object -> object.kind().equals(expected.kind())
                && object.schema().equals(expected.schema())
                && object.table().equals(expected.table())
                && object.name().equals(expected.name()));
        objects.add(new MigrationCatalogContract.CatalogObject(
                expected.kind(), expected.schema(), expected.table(), expected.name(), "changed-definition"
        ));

        MigrationCatalogVerifier.VerificationResult result = verifier.verify(new MigrationCatalogSnapshot(objects));

        assertThat(result.verified()).isFalse();
        assertThat(result.definitionMismatches()).contains(expected.canonicalName());
    }

    @Test
    void shouldRejectUnexpectedColumnAndConstraint() {
        MigrationCatalogContract contract = MigrationCatalogContract.load(CONTRACT);
        MigrationCatalogVerifier verifier = new MigrationCatalogVerifier(contract);
        HashSet<MigrationCatalogContract.CatalogObject> objects = completeObjects(contract);
        objects.add(new MigrationCatalogContract.CatalogObject(
                "column", "public", "agent", "unexpected_release_column", ""
        ));
        objects.add(new MigrationCatalogContract.CatalogObject(
                "constraint", "public", "agent", "chk_unexpected_release", "c:true"
        ));

        MigrationCatalogVerifier.VerificationResult result = verifier.verify(new MigrationCatalogSnapshot(objects));

        assertThat(result.verified()).isFalse();
        assertThat(result.unexpectedObjects()).contains(
                "column:public:agent:unexpected_release_column:",
                "constraint:public:agent:chk_unexpected_release:c:true"
        );
    }

    private HashSet<MigrationCatalogContract.CatalogObject> completeObjects(MigrationCatalogContract contract) {
        HashSet<MigrationCatalogContract.CatalogObject> objects = new HashSet<>(contract.requiredObjects());
        for (MigrationCatalogContract.CatalogObject definition : contract.requiredDefinitionObjects()) {
            int separator = definition.qualifier().indexOf(':');
            objects.add(new MigrationCatalogContract.CatalogObject(
                    definition.kind(),
                    definition.schema(),
                    definition.table(),
                    definition.name(),
                    definition.qualifier().substring(separator + 1)
            ));
        }
        return objects;
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
