package io.reladynamo.core.mapping;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Pure function from a Reladomo {@code *MithraObject.xml} document to an {@link EntityMapping}.
 *
 * <p>The only I/O is reading the caller's bytes; there are no AWS calls, clocks, or classloading
 * of {@code infinityDate} snippets. External entity resolution is disabled.
 */
public final class MithraObjectXmlParser {

    private static final Timestamp CONVENTIONAL_INFINITY = conventionalInfinity();

    public EntityMapping parse(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("xml file is required");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ReladynamoConfigException(
                    "RELADYNAMO-CFG-001",
                    "RELADYNAMO-CFG-001: cannot read " + file + ": " + e.getMessage(),
                    e);
        }
    }

    public EntityMapping parse(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("xml is required");
        }
        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw MappingValidator.xmlParseFailure("document has no root element", null);
        }
        String rootName = localName(root);
        if ("MithraPureObject".equals(rootName)) {
            throw MappingValidator.mithraPureObject(qualifiedName(root));
        }
        if ("MithraTempObject".equals(rootName)) {
            throw MappingValidator.mithraTempObject(qualifiedName(root));
        }
        if (!"MithraObject".equals(rootName)) {
            return null;
        }
        return toEntityMapping(root);
    }

    private EntityMapping toEntityMapping(Element root) {
        String packageName = childText(root, "PackageName");
        String className = childText(root, "ClassName");
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("ClassName is required");
        }
        String fqcn = (packageName == null || packageName.isEmpty()) ? className : packageName + "." + className;
        String table = childText(root, "DefaultTable");
        if (table == null || table.isEmpty()) {
            table = className;
        }

        List<AttributeMapping> attributes = new ArrayList<>();
        List<AsOf> asOfs = new ArrayList<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String name = localName(element);
            if ("Attribute".equals(name)) {
                attributes.add(toAttribute(fqcn, element));
            } else if ("AsOfAttribute".equals(name)) {
                asOfs.add(toAsOf(fqcn, element));
            }
        }

        // sourceAttribute is multi-tenancy routing the adapter cannot honour — refuse before it
        // silently merges tenants into one table. See MappingValidator.sourceAttributeUnsupported.
        org.w3c.dom.NodeList sourceNodes = root.getElementsByTagName("SourceAttribute");
        if (sourceNodes != null && sourceNodes.getLength() > 0) {
            Element src = (Element) sourceNodes.item(0);
            String srcName = src.hasAttribute("name") ? src.getAttribute("name") : "(unnamed)";
            throw MappingValidator.sourceAttributeUnsupported(fqcn, srcName);
        }

        boolean anyPk = false;
        for (AttributeMapping attribute : attributes) {
            if (attribute.isPrimaryKey()) {
                anyPk = true;
                break;
            }
        }
        if (!anyPk) {
            throw MappingValidator.missingPrimaryKey(fqcn);
        }

        TemporalMapping temporal = temporalMapping(asOfs);
        // The temporal boundaries are real stored columns, not metadata. Reladomo's
        // getPersistentAttributes() returns them alongside the business attributes, and the
        // differential gate compares all four exactly — so they must be declared here or the codec
        // rejects them and the boundaries never reach the item.
        attributes.addAll(temporalBoundaryAttributes(asOfs));
        return new EntityMapping(fqcn, table, temporal, attributes);
    }

    private static AttributeMapping toAttribute(String fqcn, Element element) {
        String javaName = requiredAttr(element, "name");
        String javaType = requiredAttr(element, "javaType");
        String columnName = element.hasAttribute("columnName") ? element.getAttribute("columnName") : javaName;
        if (columnName.isEmpty()) {
            columnName = javaName;
        }
        boolean primaryKey = flag(element, "primaryKey", false);
        if (flag(element, "identity", false)) {
            throw MappingValidator.identityColumn(fqcn, javaName);
        }
        if (primaryKey) {
            MappingValidator.requireSupportedPrimaryKeyType(fqcn, javaName, javaType);
        }
        boolean nullable;
        if (element.hasAttribute("nullable")) {
            nullable = flag(element, "nullable", false);
        } else {
            nullable = !primaryKey;
        }
        if (primaryKey && nullable) {
            throw new ReladynamoConfigException(
                    "RELADYNAMO-CFG-001",
                    "RELADYNAMO-CFG-001: Object " + fqcn
                            + " primary-key attribute " + javaName
                            + " is nullable; DynamoDB key attributes cannot be null.");
        }
        // maxLength is accepted (and ignored here): AttributeMapping has no slot for it.
        if (element.hasAttribute("maxLength")) {
            element.getAttribute("maxLength");
        }
        return new AttributeMapping(javaName, columnName, javaType, primaryKey, nullable);
    }

    private static AsOf toAsOf(String fqcn, Element element) {
        String name = requiredAttr(element, "name");
        if (flag(element, "infinityIsNull", false)) {
            throw MappingValidator.infinityIsNull(fqcn, name);
        }
        // from/to/infinityDate/futureExpiring are required-or-accepted here so the XML
        // surface is fully read. EntityMapping/TemporalMapping have no slots for them.
        element.getAttribute("infinityDate");
        flag(element, "futureExpiringRowsExist", false);
        return new AsOf(name,
                requiredAttr(element, "fromColumnName"),
                requiredAttr(element, "toColumnName"),
                flag(element, "isProcessingDate", false));
    }

    private static List<AttributeMapping> temporalBoundaryAttributes(List<AsOf> asOfs) {
        List<AttributeMapping> out = new ArrayList<AttributeMapping>();
        for (AsOf a : asOfs) {
            // Names match what MithraDataAccessor extracts (<axis>From / <axis>To), so the two sides
            // line up without a translation table.
            out.add(new AttributeMapping(a.name + "From", a.fromColumn, "Timestamp", false, false));
            out.add(new AttributeMapping(a.name + "To", a.toColumn, "Timestamp", false, false));
        }
        return out;
    }

    private static TemporalMapping temporalMapping(List<AsOf> asOfs) {
        if (asOfs.isEmpty()) {
            return TemporalMapping.none();
        }
        if (asOfs.size() >= 2) {
            return TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, CONVENTIONAL_INFINITY);
        }
        AsOf only = asOfs.get(0);
        TemporalMapping.Flavour flavour = only.processing
                ? TemporalMapping.Flavour.AUDIT_ONLY
                : TemporalMapping.Flavour.BUSINESS_ONLY;
        return TemporalMapping.of(flavour, CONVENTIONAL_INFINITY);
    }

    private Document parseDocument(String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw MappingValidator.xmlParseFailure("cannot configure a secure XML parser", e);
        }
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // Some JDK parsers reject these attributes; features above still disable XXE.
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // XXE / malformed input is reported as an exception, not a log line.
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("external entity resolution is disabled: " + systemId);
            });
            try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
                return builder.parse(in);
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw MappingValidator.xmlParseFailure(e.getMessage(), e);
        }
    }

    private static String childText(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(localName((Element) node))) {
                String text = node.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return null;
    }

    private static String qualifiedName(Element root) {
        String packageName = childText(root, "PackageName");
        String className = childText(root, "ClassName");
        if (className == null || className.isEmpty()) {
            return "unknown";
        }
        if (packageName == null || packageName.isEmpty()) {
            return className;
        }
        return packageName + "." + className;
    }

    private static String requiredAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required on " + localName(element));
        }
        return value;
    }

    private static boolean flag(Element element, String name, boolean defaultValue) {
        if (!element.hasAttribute(name)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(element.getAttribute(name).trim());
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        String tagged = element.getTagName();
        int colon = tagged.indexOf(':');
        return colon < 0 ? tagged : tagged.substring(colon + 1);
    }

    static Timestamp conventionalInfinity() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(9999, Calendar.DECEMBER, 1, 23, 59, 0);
        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());
        timestamp.setNanos(0);
        return timestamp;
    }

    /** Flavour derivation uses {@code processing} only; other AsOf fields are validated above. */
    private static final class AsOf {
        private final String name;
        private final String fromColumn;
        private final String toColumn;
        private final boolean processing;

        private AsOf(String name, String fromColumn, String toColumn, boolean processing) {
            this.name = name;
            this.fromColumn = fromColumn;
            this.toColumn = toColumn;
            this.processing = processing;
        }
    }
}
