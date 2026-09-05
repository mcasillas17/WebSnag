package websnag.buildlogic;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReleaseSigningTest {
    @ClassRule public static TemporaryFolder temporary = new TemporaryFolder();
    private static Map<String, String> valid;
    private static Path checkout;

    @BeforeClass
    public static void createDisposableIdentity() throws Exception {
        checkout = temporary.newFolder("checkout").toPath();
        Path key = temporary.getRoot().toPath().resolve("disposable.p12");
        String password = java.util.UUID.randomUUID().toString();
        ProcessBuilder generator = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-keystore", key.toString(), "-storetype", "PKCS12",
                "-storepass:env", "TEST_PASSWORD", "-keypass:env", "TEST_PASSWORD",
                "-alias", "disposable", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=Disposable REL-002A test", "-noprompt");
        generator.environment().put("TEST_PASSWORD", password);
        generator.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        generator.redirectError(ProcessBuilder.Redirect.DISCARD);
        assertEquals("disposable identity generation", 0, generator.start().waitFor());
        KeyStore store = KeyStore.getInstance(key.toFile(), password.toCharArray());
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(store.getCertificate("disposable").getEncoded()));
        valid = Map.of(
                "KEYSTORE_PATH", key.toString(), "KEYSTORE_PASSWORD", password,
                "KEY_ALIAS", "disposable", "KEY_PASSWORD", password,
                "WEBSNAG_SIGNING_CERT_SHA256", digest);
    }

    @Test
    public void acceptsTheExpectedPrivateSigningIdentity() {
        assertNotNull(ReleaseSigning.load(valid, checkout));
    }

    @Test
    public void rejectsMissingEmptyAndBlankInputsWithoutEchoingValues() {
        for (String field : valid.keySet()) {
            Map<String, String> missing = new HashMap<>(valid);
            missing.remove(field);
            assertRejected(missing, field);
            for (String blank : List.of("", " ", "\t\n")) {
                Map<String, String> input = new HashMap<>(valid);
                input.put(field, blank);
                assertRejected(input, field);
            }
        }
    }

    @Test
    public void rejectsIncorrectPasswordsAliasAndCertificate() {
        for (String field : List.of("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS")) {
            Map<String, String> input = new HashMap<>(valid);
            input.put(field, "PRIVATE_SENTINEL_VALUE");
            assertRejected(input, field.equals("KEY_ALIAS") ? "private key and X.509 certificate" : "could not read");
        }
        for (String digest : List.of("not-hex", "0".repeat(64), "a".repeat(63), "a".repeat(65))) {
            Map<String, String> input = new HashMap<>(valid);
            input.put("WEBSNAG_SIGNING_CERT_SHA256", digest);
            assertRejected(input, "certificate");
        }
    }

    @Test
    public void rejectsNonexistentMalformedAndCheckoutKeystores() throws Exception {
        Path malformed = temporary.newFile("PRIVATE_SENTINEL_VALUE.p12").toPath();
        Files.writeString(malformed, "not a keystore");
        Path tracked = checkout.resolve("signing.p12");
        Files.copy(Path.of(valid.get("KEYSTORE_PATH")), tracked);
        for (Path path : List.of(malformed, temporary.getRoot().toPath().resolve("absent"), tracked)) {
            Map<String, String> input = new HashMap<>(valid);
            input.put("KEYSTORE_PATH", path.toString());
            assertRejected(input, "Release signing");
        }
    }

    @Test
    public void verifiesEveryBundlePayloadAndRejectsTamperingUnsignedContentAndWrongSigner() throws Exception {
        Path bundle = temporary.getRoot().toPath().resolve("test.aab");
        try (var jar = new JarOutputStream(Files.newOutputStream(bundle))) {
            jar.putNextEntry(new JarEntry("base/manifest/AndroidManifest.xml"));
            jar.write("synthetic manifest payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("base/assets/test"));
            jar.write(1);
            jar.closeEntry();
        }
        String digest = valid.get("WEBSNAG_SIGNING_CERT_SHA256");
        assertThrows(IllegalArgumentException.class, () -> ReleaseArtifactIdentity.verifyBundle(bundle, digest));
        var signer = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "jarsigner").toString(),
                "-keystore", valid.get("KEYSTORE_PATH"), "-storepass:env", "TEST_PASSWORD",
                "-keypass:env", "TEST_PASSWORD", bundle.toString(), "disposable");
        signer.environment().put("TEST_PASSWORD", valid.get("KEYSTORE_PASSWORD"));
        signer.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        assertEquals(0, signer.start().waitFor());
        ReleaseArtifactIdentity.verifyBundle(bundle, digest);
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactIdentity.verifyBundle(bundle, "0".repeat(64)));
        for (String alteration : List.of("tamper", "unsigned", "signature-file", "removed",
                "case-sf", "case-block", "case-manifest")) {
            Path altered = temporary.getRoot().toPath().resolve("altered-" + alteration + ".aab");
            try (var source = new JarFile(bundle.toFile());
                    var output = new JarOutputStream(Files.newOutputStream(altered))) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (alteration.equals("removed") && entry.getName().equals("base/assets/test")) continue;
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (alteration.equals("tamper") && entry.getName().equals("base/manifest/AndroidManifest.xml")) {
                        output.write("tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                    }
                    output.closeEntry();
                    if ((alteration.equals("case-sf") && entry.getName().endsWith(".SF"))
                            || (alteration.equals("case-block") && entry.getName().endsWith(".RSA"))
                            || (alteration.equals("case-manifest") && entry.getName().equals("META-INF/MANIFEST.MF"))) {
                        output.putNextEntry(new JarEntry(entry.getName().toLowerCase(java.util.Locale.ROOT)));
                        try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                        output.closeEntry();
                    }
                }
                if (alteration.equals("unsigned") || alteration.equals("signature-file")) {
                    output.putNextEntry(new JarEntry(alteration.equals("signature-file")
                            ? "META-INF/PAYLOAD.RSA" : "META-INF/services/unsigned"));
                    output.write(1);
                    output.closeEntry();
                }
            }
            assertThrows(IllegalArgumentException.class, () -> ReleaseArtifactIdentity.verifyBundle(altered, digest));
        }
    }

    private static void assertRejected(Map<String, String> input, String message) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ReleaseSigning.load(input, checkout));
        assertTrue(error.getMessage(), error.getMessage().contains(message));
        assertFalse(error.getMessage().contains("PRIVATE_SENTINEL_VALUE"));
        assertFalse(error.getMessage().contains(valid.get("KEYSTORE_PASSWORD")));
        assertFalse(error.getMessage().contains(temporary.getRoot().toString()));
        assertNull("provider errors may expose paths or aliases", error.getCause());
    }
}
