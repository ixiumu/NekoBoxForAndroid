package com.google.api.client.googleapis;

import com.google.api.client.util.SecurityUtils;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoogleUtils {

    public static final String VERSION = getVersion();
    public static final Integer MAJOR_VERSION;
    public static final Integer MINOR_VERSION;
    public static final Integer BUGFIX_VERSION;

    @VisibleForTesting
    static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(-SNAPSHOT)?");

    static {
        Matcher versionMatcher = VERSION_PATTERN.matcher(VERSION);
        versionMatcher.find();
        MAJOR_VERSION = Integer.parseInt(versionMatcher.group(1));
        MINOR_VERSION = Integer.parseInt(versionMatcher.group(2));
        BUGFIX_VERSION = Integer.parseInt(versionMatcher.group(3));
    }

    static KeyStore certTrustStore;

    public static synchronized KeyStore getCertificateTrustStore()
            throws IOException, GeneralSecurityException {
        if (certTrustStore == null) {
            certTrustStore = SecurityUtils.getPkcs12KeyStore();
            InputStream keyStoreStream = GoogleUtils.class.getResourceAsStream("google.p12");
            SecurityUtils.loadKeyStore(certTrustStore, keyStoreStream, "notasecret");
        }
        return certTrustStore;
    }

    private static String getVersion() {
        return "2.2.0";
    }

    private GoogleUtils() {}
}
