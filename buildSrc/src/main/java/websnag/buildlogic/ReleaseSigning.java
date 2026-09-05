package websnag.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class ReleaseSigning {
    private final Path storeFile;
    private final String storeType;
    private final String storePassword;
    private final String keyAlias;
    private final String keyPassword;

    private ReleaseSigning(Path file, String type, String password, String alias, String keyPassword) {
        this.storeFile = file;
        this.storeType = type;
        this.storePassword = password;
        this.keyAlias = alias;
        this.keyPassword = keyPassword;
    }

    public Path getStoreFile() { return storeFile; }
    public String getStoreType() { return storeType; }
    public String getStorePassword() { return storePassword; }
    public String getKeyAlias() { return keyAlias; }
    public String getKeyPassword() { return keyPassword; }

    public static ReleaseSigning load(Map<String, String> environment, Path checkout) {
        String path = required(environment, "KEYSTORE_PATH");
        String password = required(environment, "KEYSTORE_PASSWORD");
        String alias = required(environment, "KEY_ALIAS");
        String keyPassword = required(environment, "KEY_PASSWORD");
        String expected = certificateDigest(required(environment, "WEBSNAG_SIGNING_CERT_SHA256"));
        char[] storeChars = password.toCharArray();
        char[] keyChars = keyPassword.toCharArray();
        try {
            Path file = checkout.resolve(path).toRealPath();
            if (file.startsWith(checkout.toRealPath()) || !Files.isRegularFile(file)
                    || Files.size(file) == 0 || Files.size(file) > 1_048_576) {
                throw new IllegalArgumentException(
                        "Release signing requires a nonempty keystore outside the checkout (maximum 1 MiB).");
            }
            KeyStore store = KeyStore.getInstance(file.toFile(), storeChars);
            if (!(store.getKey(alias, keyChars) instanceof PrivateKey)
                    || !(store.getCertificate(alias) instanceof X509Certificate certificate)) {
                throw new IllegalArgumentException("Release signing requires a private key and X.509 certificate.");
            }
            certificate.checkValidity();
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException("Release signing certificate does not match the expected SHA-256.");
            }
            return new ReleaseSigning(file, store.getType(), password, alias, keyPassword);
        } catch (IOException | GeneralSecurityException | InvalidPathException | SecurityException error) {
            // Provider errors can contain a private path or alias. Do not chain them.
            throw new IllegalArgumentException(
                    "Release signing could not read the keystore/private key. Check KEYSTORE_PATH, "
                            + "KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD and certificate validity.");
        } finally {
            // Scrub these temporary copies; AGP still requires String credentials until the build exits.
            Arrays.fill(storeChars, '\0');
            Arrays.fill(keyChars, '\0');
        }
    }

    public static String certificateDigest(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "Release signing certificate WEBSNAG_SIGNING_CERT_SHA256 must be 64 hexadecimal characters.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Release signing requires nonblank " + name + ".");
        }
        return value;
    }
}
