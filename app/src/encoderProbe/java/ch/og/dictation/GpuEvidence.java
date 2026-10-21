package ch.og.dictation;

import android.content.Context;
import android.content.pm.FeatureInfo;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Physical adapter/driver observation for this process, read after a runtime session exists:
 * GPU driver libraries actually mapped (with file digests), GPU device nodes actually open,
 * and the Vulkan features Android declares. Read-only; never fails the caller's run.
 */
final class GpuEvidence {
    private static final Pattern DRIVER = Pattern.compile(
        "(?i)^/(vendor|odm|system|apex)/.*(vulkan|pvr|powervr|img|gpu|mali|adreno|libsrv|gles|egl).*\\.so$");
    private static final Pattern NODE = Pattern.compile("^/dev/(dri/.*|pvr.*|mali.*|kgsl.*|img.*|gpu.*)$");

    private GpuEvidence() {}

    static JSONObject collect(Context context) throws Exception {
        TreeSet<String> libraries = new TreeSet<>();
        for (String line : Files.readAllLines(new File("/proc/self/maps").toPath())) {
            int slash = line.indexOf('/');
            if (slash >= 0 && DRIVER.matcher(line.substring(slash)).matches()) libraries.add(line.substring(slash));
        }
        JSONArray mapped = new JSONArray();
        for (String path : libraries) {
            JSONObject row = new JSONObject().put("path", path);
            try { row.put("sha256", sha(new File(path))); } catch (Exception error) { row.put("sha256_error", error.getClass().getSimpleName()); }
            mapped.put(row);
        }
        TreeSet<String> nodes = new TreeSet<>();
        File[] fds = new File("/proc/self/fd").listFiles();
        if (fds != null) for (File fd : fds) {
            try {
                String target = Files.readSymbolicLink(fd.toPath()).toString();
                if (NODE.matcher(target).matches()) nodes.add(target);
            } catch (Exception ignored) {
                // Descriptor closed while listing.
            }
        }
        JSONArray vulkan = new JSONArray();
        FeatureInfo[] features = context.getPackageManager().getSystemAvailableFeatures();
        if (features != null) for (FeatureInfo feature : features)
            if (feature.name != null && feature.name.startsWith("android.hardware.vulkan"))
                vulkan.put(new JSONObject().put("name", feature.name).put("version", feature.version));
        return new JSONObject().put("mapped_gpu_libraries", mapped).put("device_nodes", new JSONArray(nodes))
            .put("vulkan_features", vulkan).put("ep_devices", new JSONArray());
    }

    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 20]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
