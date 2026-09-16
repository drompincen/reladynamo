package io.reladynamo.core.mapping;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three demo projects are the known-good Reladomo corpus: non-dated, audit-only,
 * business-only, and bitemporal objects as they appear in working applications.
 */
final class MithraObjectXmlCorpusTest {

    private final MithraObjectXmlParser parser = new MithraObjectXmlParser();

    @Test
    void should_parse_every_mithra_object_in_the_three_demo_projects() throws IOException {
        assertThat(xmlFilesUnderDemoReladomo().size())
                .as("expected ~80 XML files across the three demo reladomo trees")
                .isGreaterThanOrEqualTo(79);
        List<Path> objectFiles = mithraObjectFiles();
        assertThat(objectFiles.size())
                .as("expected ~80 real object XMLs across the three demos")
                .isGreaterThanOrEqualTo(70);

        Map<TemporalMapping.Flavour, Integer> flavours = new EnumMap<>(TemporalMapping.Flavour.class);
        List<String> parsed = new ArrayList<>();
        for (Path file : objectFiles) {
            EntityMapping mapping = parser.parse(file);
            assertThat(mapping).as(file.toString()).isNotNull();
            assertThat(mapping.className()).isNotBlank();
            assertThat(mapping.tableName()).isNotBlank();
            assertThat(mapping.primaryKeyAttributes()).isNotEmpty();
            TemporalMapping.Flavour flavour = mapping.temporal().flavour();
            Integer count = flavours.get(flavour);
            flavours.put(flavour, count == null ? 1 : count + 1);
            parsed.add(mapping.className() + ":" + flavour);
        }

        assertThat(flavours.get(TemporalMapping.Flavour.NONE)).isNotNull();
        assertThat(flavours.get(TemporalMapping.Flavour.BUSINESS_ONLY)).isNotNull();
        assertThat(flavours.get(TemporalMapping.Flavour.BITEMPORAL)).isNotNull();
        assertThat(flavours.get(TemporalMapping.Flavour.AUDIT_ONLY)).isNotNull();
        assertThat(parsed).isNotEmpty();
    }

    @Test
    void should_parse_car_as_non_temporal() {
        EntityMapping mapping = parser.parse(demoFile(
                "demos/03-car-classifier/project/src/main/resources/reladomo/models/Car.xml"));

        assertThat(mapping.className()).isEqualTo("com.reladynamo.demo.classifier.domain.Car");
        assertThat(mapping.tableName()).isEqualTo("CAR");
        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.NONE);
        assertThat(mapping.attribute("carId").isPrimaryKey()).isTrue();
        assertThat(mapping.attribute("make").itemName()).isEqualTo("MAKE");
        assertThat(mapping.attribute("make").isNullable()).isFalse();
    }

    @Test
    void should_parse_classification_result_as_audit_only() {
        EntityMapping mapping = parser.parse(demoFile(
                "demos/03-car-classifier/project/src/main/resources/reladomo/models/ClassificationResult.xml"));

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.AUDIT_ONLY);
        assertThat(mapping.temporal().infinity())
                .isEqualTo(MithraObjectXmlParserTest.conventionalInfinity());
    }

    @Test
    void should_parse_pet_as_business_only() {
        EntityMapping mapping = parser.parse(demoFile(
                "demos/02-petstore-unitemporal/project/src/main/resources/reladomo/Pet.xml"));

        assertThat(mapping.className()).isEqualTo("com.reladynamo.demo.petstore.domain.Pet");
        assertThat(mapping.tableName()).isEqualTo("PET");
        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BUSINESS_ONLY);
        assertThat(mapping.attribute("petId").isPrimaryKey()).isTrue();
        assertThat(mapping.attribute("breedId").isNullable()).isTrue();
    }

    @Test
    void should_parse_customer_as_bitemporal() {
        EntityMapping mapping = parser.parse(demoFile(
                "demos/01-crm-bitemporal/project/src/main/resources/reladomo/models/Customer.xml"));

        assertThat(mapping.className()).isEqualTo("com.reladynamo.demo.crm.domain.Customer");
        assertThat(mapping.tableName()).isEqualTo("CUSTOMER");
        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BITEMPORAL);
        assertThat(mapping.primaryKeyAttributes()).extracting(a -> a.javaName()).containsExactly("customerId");
        assertThat(mapping.temporal().infinity())
                .isEqualTo(MithraObjectXmlParserTest.conventionalInfinity());
    }

    @Test
    void should_skip_resource_lists_and_runtime_xml_that_are_not_mithra_objects() throws IOException {
        List<Path> xmlFiles = xmlFilesUnderDemoReladomo();
        List<Path> nonObjects = new ArrayList<>();
        for (Path file : xmlFiles) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (!isMithraObject(text) && !isMithraPureOrTemp(text)) {
                nonObjects.add(file);
            }
        }
        assertThat(nonObjects).isNotEmpty();

        for (Path file : nonObjects) {
            assertThat(parser.parse(file)).isNull();
        }
    }

    private static List<Path> mithraObjectFiles() throws IOException {
        List<Path> objects = new ArrayList<>();
        for (Path file : xmlFilesUnderDemoReladomo()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (isMithraObject(text)) {
                objects.add(file);
            }
        }
        return objects;
    }

    private static List<Path> xmlFilesUnderDemoReladomo() throws IOException {
        Path root = repoRoot();
        Path[] dirs = new Path[]{
                root.resolve("demos/03-car-classifier/project/src/main/resources/reladomo"),
                root.resolve("demos/01-crm-bitemporal/project/src/main/resources/reladomo"),
                root.resolve("demos/02-petstore-unitemporal/project/src/main/resources/reladomo")
        };
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            assertThat(dir).as("demo XML directory").exists();
            try (Stream<Path> walk = Files.walk(dir)) {
                files.addAll(walk
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml"))
                        .collect(Collectors.toList()));
            }
        }
        return files;
    }

    private static Path demoFile(String relative) {
        Path file = repoRoot().resolve(relative);
        assertThat(file).exists();
        return file;
    }

    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 12 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("demos/01-crm-bitemporal"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "cannot find reladynamo repo root from " + System.getProperty("user.dir"));
    }

    private static boolean isMithraObject(String xml) {
        // Must not match MithraObjectResource / MithraObjectConfiguration.
        return xml.contains("<MithraObject ") || xml.contains("<MithraObject>");
    }

    private static boolean isMithraPureOrTemp(String xml) {
        return xml.contains("<MithraPureObject") || xml.contains("<MithraTempObject");
    }
}
