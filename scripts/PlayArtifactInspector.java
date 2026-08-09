import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class PlayArtifactInspector {
    private static final String ANDROID_NAMESPACE =
        "http://schemas.android.com/apk/res/android";
    private static final Map<String, Long> FOREGROUND_SERVICE_TYPES = Map.ofEntries(
        Map.entry("dataSync", 0x01L),
        Map.entry("mediaPlayback", 0x02L),
        Map.entry("phoneCall", 0x04L),
        Map.entry("location", 0x08L),
        Map.entry("connectedDevice", 0x10L),
        Map.entry("mediaProjection", 0x20L),
        Map.entry("camera", 0x40L),
        Map.entry("microphone", 0x80L),
        Map.entry("health", 0x100L),
        Map.entry("remoteMessaging", 0x200L),
        Map.entry("systemExempted", 0x400L),
        Map.entry("shortService", 0x800L),
        Map.entry("fileManagement", 0x1000L),
        Map.entry("mediaProcessing", 0x2000L),
        Map.entry("specialUse", 0x40000000L)
    );
    private static final Map<String, Long> CONFIG_CHANGES = Map.ofEntries(
        Map.entry("mcc", 0x0001L),
        Map.entry("mnc", 0x0002L),
        Map.entry("locale", 0x0004L),
        Map.entry("touchscreen", 0x0008L),
        Map.entry("keyboard", 0x0010L),
        Map.entry("keyboardHidden", 0x0020L),
        Map.entry("navigation", 0x0040L),
        Map.entry("orientation", 0x0080L),
        Map.entry("screenLayout", 0x0100L),
        Map.entry("uiMode", 0x0200L),
        Map.entry("screenSize", 0x0400L),
        Map.entry("smallestScreenSize", 0x0800L),
        Map.entry("density", 0x1000L),
        Map.entry("layoutDirection", 0x2000L),
        Map.entry("colorMode", 0x4000L),
        Map.entry("grammaticalGender", 0x8000L),
        Map.entry("resourcesUnused", 0x08000000L),
        Map.entry("fontWeightAdjustment", 0x10000000L),
        Map.entry("fontScale", 0x40000000L),
        Map.entry("assetsPaths", 0x80000000L)
    );

    private PlayArtifactInspector() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("manifest-contract")) {
                System.out.print(manifestContract(System.in));
                return;
            }
            if (args.length == 3 && args[0].equals("verify-signers")) {
                verifySigners(Path.of(args[1]), normalizeFingerprint(args[2]));
                System.out.print("Android App Bundle signer coverage verified.\n");
                return;
            }
            throw new IllegalArgumentException("Unsupported Play artifact inspector invocation.");
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) {
                message = "the artifact did not satisfy the release contract";
            }
            System.err.println("Play artifact inspection failed: " + message);
            System.exit(1);
        }
    }

    private static void verifySigners(Path artifact, String expectedFingerprint) throws Exception {
        Set<String> expectedSigners = Set.of(expectedFingerprint);
        Set<String> commonSigners = null;
        int contentEntries = 0;
        try (JarFile jar = new JarFile(artifact.toFile(), true)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || isJarSignatureMetadata(entry.getName())) {
                    continue;
                }
                contentEntries += 1;
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                CodeSigner[] codeSigners = entry.getCodeSigners();
                if (codeSigners == null || codeSigners.length == 0) {
                    throw new SecurityException("every content entry must be signed");
                }
                Set<String> entrySigners = new TreeSet<>();
                for (CodeSigner signer : codeSigners) {
                    List<? extends Certificate> certificates =
                        signer.getSignerCertPath().getCertificates();
                    if (certificates.isEmpty()) {
                        throw new SecurityException("a content signer has no certificate");
                    }
                    entrySigners.add(sha256(certificates.get(0).getEncoded()));
                }
                if (commonSigners == null) {
                    commonSigners = entrySigners;
                } else if (!commonSigners.equals(entrySigners)) {
                    throw new SecurityException("all content entries must share one signer set");
                }
            }
        }
        if (contentEntries == 0) {
            throw new SecurityException("the artifact contains no signed content");
        }
        if (!expectedSigners.equals(commonSigners)) {
            throw new SecurityException("the artifact signer does not match the approved upload certificate");
        }
    }

    private static boolean isJarSignatureMetadata(String name) {
        String normalized = name.toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("META-INF/MANIFEST.MF")) {
            return true;
        }
        if (!normalized.startsWith("META-INF/")) {
            return false;
        }
        String topLevelName = normalized.substring("META-INF/".length());
        if (topLevelName.contains("/")) {
            return false;
        }
        return topLevelName.endsWith(".SF") ||
            topLevelName.endsWith(".RSA") ||
            topLevelName.endsWith(".DSA") ||
            topLevelName.endsWith(".EC") ||
            isCustomSignatureControlFile(topLevelName);
    }

    private static boolean isCustomSignatureControlFile(String topLevelName) {
        if (!topLevelName.startsWith("SIG-")) {
            return false;
        }
        int extensionSeparator = topLevelName.lastIndexOf('.');
        if (extensionSeparator < 0) {
            return true;
        }
        String extension = topLevelName.substring(extensionSeparator + 1);
        return extension.matches("[A-Z0-9]{1,3}");
    }

    private static String manifestContract(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(input);
        Element manifest = document.getDocumentElement();
        if (!manifest.getTagName().equals("manifest")) {
            throw new IllegalArgumentException("the document root is not a manifest");
        }
        String packageName = requiredAttribute(manifest, null, "package", "manifest package");
        Element usesSdk = exactlyOneChild(manifest, "uses-sdk");
        Element application = exactlyOneChild(manifest, "application");

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("packageName", packageName);
        contract.put(
            "versionCode",
            parseInteger(requiredAndroidAttribute(manifest, "versionCode"), "versionCode")
        );
        contract.put("versionName", requiredAndroidAttribute(manifest, "versionName"));
        contract.put(
            "minSdk",
            parseInteger(requiredAndroidAttribute(usesSdk, "minSdkVersion"), "minSdkVersion")
        );
        contract.put(
            "targetSdk",
            parseInteger(requiredAndroidAttribute(usesSdk, "targetSdkVersion"), "targetSdkVersion")
        );
        contract.put("usesSdkAttributes", allAndroidAttributes(usesSdk));
        contract.put(
            "applicationName",
            qualifyComponent(requiredAndroidAttribute(application, "name"), packageName)
        );
        contract.put(
            "applicationSecurityAttributes",
            componentAndroidAttributes(application, packageName)
        );
        contract.put("permissions", permissionContracts(manifest));
        contract.put("declaredPermissions", topLevelContracts(manifest, "permission"));
        contract.put("permissionGroups", topLevelContracts(manifest, "permission-group"));
        contract.put("permissionTrees", topLevelContracts(manifest, "permission-tree"));
        contract.put("activities", componentContracts(application, "activity", packageName));
        contract.put(
            "activityAliases",
            componentContracts(application, "activity-alias", packageName)
        );
        contract.put("services", componentContracts(application, "service", packageName));
        contract.put("receivers", componentContracts(application, "receiver", packageName));
        contract.put("providers", componentContracts(application, "provider", packageName));
        return json(contract);
    }

    private static List<Map<String, String>> topLevelContracts(
        Element manifest,
        String elementName
    ) {
        List<Map<String, String>> values = new ArrayList<>();
        for (Element element : directChildren(manifest, elementName)) {
            Map<String, String> attributes = allAndroidAttributes(element);
            if (!attributes.containsKey("android:name")) {
                throw new IllegalArgumentException("a " + elementName + " element has no name");
            }
            values.add(attributes);
        }
        values.sort(Comparator.comparing(PlayArtifactInspector::json));
        rejectDuplicateNames(values, elementName);
        return values;
    }

    private static List<Map<String, String>> permissionContracts(Element manifest) {
        List<Map<String, String>> permissions = new ArrayList<>();
        for (Element child : childElements(manifest)) {
            if (child.getTagName().matches("uses-permission(?:-sdk-\\d+)?")) {
                Map<String, String> attributes = allAndroidAttributes(child);
                if (!attributes.containsKey("android:name")) {
                    throw new IllegalArgumentException("a uses-permission element has no name");
                }
                permissions.add(attributes);
            }
        }
        permissions.sort(Comparator.comparing(PlayArtifactInspector::json));
        rejectDuplicateNames(permissions, "permission");
        return permissions;
    }

    private static List<Map<String, Object>> componentContracts(
        Element application,
        String elementName,
        String packageName
    ) {
        List<Map<String, Object>> components = new ArrayList<>();
        for (Element component : directChildren(application, elementName)) {
            String name = qualifyComponent(requiredAndroidAttribute(component, "name"), packageName);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put(
                "securityAttributes",
                componentAndroidAttributes(component, packageName)
            );
            value.put("intentFilters", intentFilterContracts(component));
            components.add(value);
        }
        components.sort(Comparator.comparing(PlayArtifactInspector::json));
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> component : components) {
            if (!names.add((String) component.get("name"))) {
                throw new IllegalArgumentException("duplicate " + elementName + " name");
            }
        }
        return components;
    }

    private static List<Map<String, Object>> intentFilterContracts(Element component) {
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Element filter : directChildren(component, "intent-filter")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("attributes", allAndroidAttributes(filter));
            value.put("actions", namedChildren(filter, "action"));
            value.put("categories", namedChildren(filter, "category"));
            List<Map<String, String>> data = new ArrayList<>();
            for (Element dataElement : directChildren(filter, "data")) {
                data.add(allAndroidAttributes(dataElement));
            }
            data.sort(Comparator.comparing(PlayArtifactInspector::json));
            value.put("data", data);
            filters.add(value);
        }
        filters.sort(Comparator.comparing(PlayArtifactInspector::json));
        return filters;
    }

    private static List<String> namedChildren(Element parent, String elementName) {
        Set<String> names = new TreeSet<>();
        for (Element element : directChildren(parent, elementName)) {
            String name = requiredAndroidAttribute(element, "name");
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate " + elementName + " name");
            }
        }
        return new ArrayList<>(names);
    }

    private static Map<String, String> componentAndroidAttributes(
        Element element,
        String packageName
    ) {
        Map<String, String> attributes = allAndroidAttributes(element);
        for (String name : List.of("android:name", "android:targetActivity")) {
            if (attributes.containsKey(name)) {
                attributes.put(name, qualifyComponent(attributes.get(name), packageName));
            }
        }
        attributes.remove("android:name");
        return attributes;
    }

    private static Map<String, String> allAndroidAttributes(Element element) {
        Map<String, String> attributes = new TreeMap<>();
        NamedNodeMap nodes = element.getAttributes();
        for (int index = 0; index < nodes.getLength(); index += 1) {
            Node node = nodes.item(index);
            if (ANDROID_NAMESPACE.equals(node.getNamespaceURI())) {
                String name = node.getLocalName();
                String value = node.getNodeValue();
                if (name.equals("foregroundServiceType")) {
                    value = normalizeForegroundServiceType(value);
                }
                if (name.equals("configChanges")) {
                    value = normalizeFlags(value, CONFIG_CHANGES, "configuration change");
                }
                if (name.equals("protectionLevel")) {
                    value = normalizeProtectionLevel(value);
                }
                attributes.put("android:" + name, value);
            }
        }
        return attributes;
    }

    private static String normalizeProtectionLevel(String value) {
        if (value.matches("(?i)^0x[0-9a-f]+$")) {
            return "0x" + Long.toHexString(Long.decode(value));
        }
        return switch (value) {
            case "normal" -> "0x0";
            case "dangerous" -> "0x1";
            case "signature" -> "0x2";
            case "signatureOrSystem" -> "0x3";
            default -> throw new IllegalArgumentException("unknown permission protection level");
        };
    }

    private static String normalizeForegroundServiceType(String value) {
        return normalizeFlags(value, FOREGROUND_SERVICE_TYPES, "foreground-service type");
    }

    private static String normalizeFlags(
        String value,
        Map<String, Long> flags,
        String label
    ) {
        if (value.matches("(?i)^0x[0-9a-f]+$")) {
            return "0x" + Long.toHexString(Long.decode(value));
        }
        long combined = 0;
        for (String rawFlag : value.split("\\|")) {
            String flag = rawFlag.trim();
            Long bit = flags.get(flag);
            if (bit == null) {
                throw new IllegalArgumentException("unknown " + label);
            }
            combined |= bit;
        }
        return "0x" + Long.toHexString(combined);
    }

    private static Element exactlyOneChild(Element parent, String name) {
        List<Element> children = directChildren(parent, name);
        if (children.size() != 1) {
            throw new IllegalArgumentException("expected exactly one " + name + " element");
        }
        return children.get(0);
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        for (Element child : childElements(parent)) {
            if (child.getTagName().equals(name)) {
                matches.add(child);
            }
        }
        return matches;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index += 1) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) child);
            }
        }
        return elements;
    }

    private static String requiredAndroidAttribute(Element element, String name) {
        return requiredAttribute(element, ANDROID_NAMESPACE, name, "android:" + name);
    }

    private static String requiredAttribute(
        Element element,
        String namespace,
        String name,
        String label
    ) {
        String value = namespace == null
            ? element.getAttribute(name)
            : element.getAttributeNS(namespace, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + label);
        }
        return value;
    }

    private static long parseInteger(String value, String label) {
        try {
            long parsed = Long.decode(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static String qualifyComponent(String name, String packageName) {
        if (name.startsWith(".")) {
            return packageName + name;
        }
        if (!name.contains(".")) {
            return packageName + "." + name;
        }
        return name;
    }

    private static void rejectDuplicateNames(List<Map<String, String>> values, String label) {
        Set<String> names = new TreeSet<>();
        for (Map<String, String> value : values) {
            if (!names.add(value.get("android:name"))) {
                throw new IllegalArgumentException("duplicate " + label + " name");
            }
        }
    }

    private static String normalizeFingerprint(String value) {
        String normalized = value.replace(":", "").toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("the approved upload-certificate fingerprint is invalid");
        }
        return normalized;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(quote(entry.getKey().toString()));
                result.append(':');
                result.append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(json(item));
            }
            return result.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported manifest-contract value");
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
