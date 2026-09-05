package websnag.buildlogic;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.HashSet;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class ReleaseArtifactIdentity {
    private ReleaseArtifactIdentity() {}

    public static void verifyBundle(Path bundle, String expectedDigest) {
        String expected = ReleaseSigning.certificateDigest(expectedDigest);
        try (JarFile jar = new JarFile(bundle.toFile(), true)) {
            if (jar.getJarEntry("base/manifest/AndroidManifest.xml") == null) {
                throw new IllegalArgumentException("AAB base manifest is missing.");
            }
            var entries = jar.entries();
            var names = new HashSet<String>();
            var signatureFiles = new HashSet<String>();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!names.add(name)) throw new IllegalArgumentException("AAB contains duplicate entries.");
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.equals("META-INF/MANIFEST.MF") && !name.equals(upper)) {
                    throw new IllegalArgumentException("AAB contains a noncanonical JAR manifest name.");
                }
                if (upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") && !signatureFiles.add(upper)) {
                    throw new IllegalArgumentException("AAB contains case-variant duplicate signing metadata.");
                }
            }
            var sf = signatureFiles.stream().filter(name -> name.endsWith(".SF")).toList();
            if (signatureFiles.size() != 2 || sf.size() != 1
                    || signatureFiles.stream().filter(name -> !name.endsWith(".SF"))
                        .noneMatch(name -> name.substring(0, name.lastIndexOf('.'))
                            .equals(sf.get(0).substring(0, sf.get(0).length() - 3)))) {
                throw new IllegalArgumentException("AAB must have one matching signature-file/block pair.");
            }
            if (jar.getManifest() == null || !names.containsAll(jar.getManifest().getEntries().keySet())) {
                throw new IllegalArgumentException("AAB is missing signed entries.");
            }
            entries = jar.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().toUpperCase(Locale.ROOT);
                if (name.equals("META-INF/MANIFEST.MF")
                        || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                try (var stream = jar.getInputStream(entry)) {
                    while (stream.read(buffer) != -1) {
                        // Reading to EOF forces JarFile to verify this payload's signature.
                    }
                }
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) {
                    throw new IllegalArgumentException("AAB contains unsigned or multiply signed payload.");
                }
                byte[] certificate = signers[0].getSignerCertPath().getCertificates().get(0).getEncoded();
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate));
                if (!actual.equals(expected)) {
                    throw new IllegalArgumentException("AAB certificate does not match the expected SHA-256.");
                }
            }
        } catch (IOException | GeneralSecurityException | SecurityException error) {
            throw new IllegalArgumentException("AAB signature verification failed.");
        }
    }

    public static void verifyManifest(String xml, WebSnagVersion expected) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            var document = builder.parse(new InputSource(new StringReader(xml)));
            Element manifest = document.getDocumentElement();
            String android = "http://schemas.android.com/apk/res/android";
            for (String permissionTag : new String[] {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}) {
                var permissions = manifest.getElementsByTagName(permissionTag);
                for (int index = 0; index < permissions.getLength(); index++) {
                    if (((Element) permissions.item(index)).getAttributeNS(android, "name")
                            .equals("android.permission.INTERNET")) {
                        throw new IllegalArgumentException("AAB must not request INTERNET permission.");
                    }
                }
            }
            var apps = manifest.getElementsByTagName("application");
            if (!manifest.getTagName().equals("manifest")
                    || !manifest.getAttribute("package").equals("websnag.elopenmike.com")
                    || !manifest.getAttributeNS(android, "versionName").equals(expected.getVersionName())
                    || !manifest.getAttributeNS(android, "versionCode").equals(String.valueOf(expected.getVersionCode()))
                    || apps.getLength() != 1) {
                throw new IllegalArgumentException("AAB package/version identity does not match the release tag.");
            }
            String debuggable = ((Element) apps.item(0)).getAttributeNS(android, "debuggable");
            if (!debuggable.isEmpty() && !debuggable.equals("false")) {
                throw new IllegalArgumentException("AAB must not be debuggable.");
            }
        } catch (IOException | ParserConfigurationException | SAXException error) {
            throw new IllegalArgumentException("AAB manifest could not be verified.");
        }
    }
}
