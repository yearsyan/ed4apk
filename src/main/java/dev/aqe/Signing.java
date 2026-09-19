package dev.aqe;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.KeyConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class Signing {
    static void sign(Path input, Path output, Path keyStore, String alias,
                     char[] storePassword, char[] keyPassword, boolean force) throws Exception {
        ApkArchive.validate(input);
        KeyStore store = KeyStore.getInstance(keyStore.toFile(), storePassword);
        var key = store.getKey(alias, keyPassword);
        if (!(key instanceof PrivateKey)) throw new IOException("No private key for alias: " + alias);
        PrivateKey privateKey = (PrivateKey) key;
        var chain = store.getCertificateChain(alias);
        if (chain == null || chain.length == 0) throw new IOException("No certificate chain for alias: " + alias);
        List<X509Certificate> certificates = Arrays.stream(chain).map(c -> (X509Certificate) c).collect(Collectors.toList());
        var config = new ApkSigner.SignerConfig.Builder("AQE", new KeyConfig.Jca(privateKey), certificates).build();
        int minSdk = ResourceEditor.minSdk(input);
        Outputs.write(input, output, force, temporary -> {
            new ApkSigner.Builder(List.of(config)).setInputApk(input.toFile()).setOutputApk(temporary.toFile())
                    .setMinSdkVersion(minSdk).setV1SigningEnabled(minSdk < 24)
                    .setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
                    .setOtherSignersSignaturesPreserved(false).setAlignmentPreserved(false)
                    .setLibraryPageAlignmentBytes(16384).setCreatedBy("aqe 0.1.0")
                    .build().sign();
            var result = verify(temporary);
            if (!result.isVerified()) throw new IOException("Signed output verification failed: " + result.getAllErrors());
            ApkArchive.checkAlignment(temporary);
        });
    }

    static ApkVerifier.Result verify(Path apk) throws Exception {
        return new ApkVerifier.Builder(apk.toFile()).build().verify();
    }
}
