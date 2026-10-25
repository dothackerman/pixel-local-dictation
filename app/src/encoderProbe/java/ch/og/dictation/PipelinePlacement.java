package ch.og.dictation;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;
import org.json.*;

/**
 * pipeline-placement-v1: creates the real Sherpa recognizer (Pipeline.recognizer) for a diagnostic
 * variant whose only differences from the timed one are the WebGPU encoder and session-level
 * VERBOSE ORT logging, reads this process's own ORT placement/cause lines while it is created,
 * records GPU driver evidence, then releases it. No audio, no Run, not timed.
 */
public final class PipelinePlacement {
    static final String PROTOCOL = "pipeline-placement-v1";
    private static final int MAX_LINES = 20_000;
    private final Context context;
    private final File folder;

    public PipelinePlacement(Context context, File folder) { this.context = context; this.folder = folder; }

    public void run() throws Exception {
        File result = new File(folder, "result.json");
        String attempt = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("shortEncoderAttempt", "");
        require(attempt.matches("[0-9a-f]{32}"), "wrapper-owned attempt is required");
        require(!result.exists(), "placement result already exists; no retry");
        JSONObject out = new JSONObject().put("schema", 1).put("protocol", PROTOCOL).put("attempt_id", attempt)
            .put("eligible", false).put("outcome", "failed").put("scope", "untimed placement and adapter diagnostic; no audio, no Run")
            .put("pid", android.os.Process.myPid()).put("processStartElapsedMs", android.os.Process.getStartElapsedRealtime());
        String checkpoint = "verify";
        try {
            File config = new File(folder, "pipeline-placement.json");
            JSONObject plan = new JSONObject(Files.readString(config.toPath()));
            out.put("freeze_sha256", sha(config)).put("frozen", plan);
            require(plan.getString("protocol").equals(PROTOCOL) && plan.getInt("schema") == 1
                && plan.getInt("session_log_severity") == 0, "unsupported placement plan");
            JSONObject model = plan.getJSONObject("model");
            for (java.util.Iterator<String> it = model.keys(); it.hasNext(); ) {
                String name = it.next();
                require(sha(new File(folder, "model/" + name)).equals(model.getString(name)), "model changed: " + name);
            }
            File variantFile = new File(folder, "variant.json");
            require(sha(variantFile).equals(plan.getString("variant_sha256")), "variant changed");
            Variant variant = Variant.Companion.parse(Files.readString(variantFile.toPath()));
            require(variant.getRuntime().getEncoderProvider().equals("webgpu") && variant.getRuntime().getPlacementLog(),
                "diagnostic variant needs encoder_provider webgpu and placement_log");
            try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
                String core = sha(apk.getInputStream(apk.getEntry("lib/arm64-v8a/libonnxruntime.so")));
                require(core.equals(plan.getJSONObject("runtime").getString("core_sha256")), "runtime core changed");
                out.put("runtime_core_sha256", core);
            }
            String appSha = sha(new File(context.getPackageCodePath()));
            require(appSha.equals(plan.getJSONObject("builds").getString("app_sha256")), "installed app differs from freeze");
            Context testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext();
            String testSha = sha(new File(testContext.getPackageCodePath()));
            require(testSha.equals(plan.getJSONObject("builds").getString("test_sha256")), "installed test differs from freeze");
            out.put("build_sha256", appSha).put("test_build_sha256", testSha).put("session_log_severity", 0);
            checkpoint = "placement-read";
            try (EncoderProbe.LogTail tail = new EncoderProbe.LogTail(attempt)) {
                checkpoint = "recognizer-create";
                long start = SystemClock.elapsedRealtimeNanos();
                Recognizer recognizer = Pipeline.INSTANCE.recognizer(variant, ModelFiles.Companion.dir(new File(folder, "model")), null, "", null);
                try {
                    out.put("recognizer_create_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6);
                    checkpoint = "placement-read";
                    out.put("placement_lines", tail.finishLines(MAX_LINES)).put("log_lines", tail.lineCount());
                    tail.close();
                    checkpoint = "gpu-evidence";
                    out.put("gpu_evidence", GpuEvidence.collect(context));
                } finally {
                    Pipeline.INSTANCE.release(recognizer);
                }
            }
            out.put("outcome", "complete").put("checkpoint", "placement-listed");
        } catch (Exception | LinkageError error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String message = trace.toString();
            out.put("checkpoint", checkpoint).put("failure", message.length() > 16384 ? message.substring(0, 16384) : message);
            android.util.Log.e("LabTest", PROTOCOL + " " + checkpoint + ": " + message);
        } finally {
            byte[] payload = out.toString(2).getBytes(StandardCharsets.UTF_8);
            require(payload.length <= 16 * 1024 * 1024, "placement receipt exceeds fixed byte bound");
            Files.write(result.toPath(), payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static String sha(File file) throws Exception { return sha(new FileInputStream(file)); }
    private static String sha(InputStream source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = source) {
            byte[] buffer = new byte[1 << 20]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
