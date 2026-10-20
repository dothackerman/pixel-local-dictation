package ch.og.dictation;

import android.content.Context;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;

/** Benchmark-only, short synthetic checkpoint. Never constructs the speech pipeline. */
public final class EncoderProbe {
    private final Context context;
    private final File folder;
    private String checkpoint = "verify";
    public EncoderProbe(Context context, File folder) { this.context = context; this.folder = folder; }

    public void run() throws Exception {
        File result = new File(folder, "result.json");
        File reservation = new File(folder, "encoder-probe-started");
        // A process death remains an unresolved attempt. Operator must preserve/reconcile it.
        if (!reservation.createNewFile()) throw new IllegalStateException("probe already reserved; reconcile existing attempt");
        JSONObject out = new JSONObject().put("schema", 1).put("eligible", false)
            .put("scope", "short synthetic feasibility only; full-take checks not run")
            .put("outcome", "failed").put("runId", UUID.randomUUID().toString())
            .put("pid", android.os.Process.myPid()).put("processStartElapsedMs", android.os.Process.getStartElapsedRealtime())
            .put("adapter_driver_proof", "unproven").put("fp32_shader_proof", "unproven")
            .put("residual_node_assignment", "unproven").put("later_checks", "not run");
        JSONArray memory = new JSONArray();
        out.put("memory", memory);
        try {
            File config = new File(folder, "encoder-probe.json");
            JSONObject plan = new JSONObject(Files.readString(config.toPath()));
            out.put("freeze_sha256", sha(config)).put("frozen", plan);
            String provider = plan.getString("provider");
            require(provider.equals("cpu") || provider.equals("webgpu"), "unknown provider " + provider);
            require(plan.getInt("schema") == 1 && plan.getInt("true_length") == 17 &&
                plan.getJSONArray("shape").toString().equals("[1,128,17]"), "unsupported frozen shape");
            for (String name : new String[]{"encoder.onnx", "encoder.weights"})
                require(sha(new File(folder, "model/" + name)).equals(plan.getJSONObject("model").getString(name)), "model changed: " + name);
            for (String name : new String[]{"audio_signal.f32", "length.i64"})
                require(sha(new File(folder, name)).equals(plan.getJSONObject("inputs").getString(name)), "input changed: " + name);
            try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
                String actual = sha(apk.getInputStream(apk.getEntry("lib/arm64-v8a/libonnxruntime.so")));
                require(actual.equals(plan.getJSONObject("runtime").getString("core_sha256")), "runtime core changed");
                out.put("runtime_core_sha256", actual);
            }
            String appSha = sha(new File(context.getPackageCodePath()));
            require(appSha.equals(plan.getJSONObject("builds").getString("app_sha256")), "installed app differs from freeze");
            out.put("build_sha256", appSha);
            Context testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext();
            try (ZipFile apk = new ZipFile(testContext.getPackageCodePath())) {
                String jni = sha(apk.getInputStream(apk.getEntry("lib/arm64-v8a/libonnxruntime4j_jni.so")));
                require(jni.equals(plan.getJSONObject("runtime").getString("jni_sha256")), "runtime JNI changed");
                out.put("runtime_jni_sha256", jni);
            }
            String testSha = sha(new File(testContext.getPackageCodePath()));
            require(testSha.equals(plan.getJSONObject("builds").getString("test_sha256")), "installed test differs from freeze");
            out.put("test_build_sha256", testSha);
            checkpoint = "native-load";
            System.loadLibrary("onnxruntime");
            OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, "encoder-probe");
            require(env.getVersion().equals("1.31.0"), "runtime version mismatch: " + env.getVersion());
            out.put("runtime_version", env.getVersion()).put("available_providers", OrtEnvironment.getAvailableProviders().toString());
            memory.put(resources("before-load"));
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(4);
                options.setInterOpNumThreads(1);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
                options.setSessionLogVerbosityLevel(1);
                options.enableProfiling(new File(folder, "encoder-probe-profile").getAbsolutePath());
                out.put("optimization", "ALL_OPT").put("execution_mode", "SEQUENTIAL").put("profiling", true)
                    .put("timing_scope", "input preparation, copies, synchronous Run, host outputs, and diagnostic persistence; profiled diagnostic only");
                checkpoint = "provider-create";
                if (provider.equals("webgpu")) {
                    options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
                    Map<String, String> gpu = new LinkedHashMap<>();
                    gpu.put("enableGraphCapture", "0");
                    gpu.put("enableInt64", "1");
                    gpu.put("preferredLayout", "NCHW");
                    gpu.put("dawnBackendType", "Vulkan");
                    options.addWebGPU(gpu);
                    out.put("provider_options", new JSONObject(gpu)).put("cpu_fallback_disabled", true);
                } else {
                    options.addCPU(true);
                    out.put("cpu_fallback_disabled", false);
                }
                checkpoint = "session-create";
                long start = SystemClock.elapsedRealtimeNanos();
                try (OrtSession session = env.createSession(new File(folder, "model/encoder.onnx").getAbsolutePath(), options)) {
                    out.put("cold_load_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6);
                    memory.put(resources("after-load"));
                    start = SystemClock.elapsedRealtimeNanos();
                    byte[] input = Files.readAllBytes(new File(folder, "audio_signal.f32").toPath());
                    byte[] length = Files.readAllBytes(new File(folder, "length.i64").toPath());
                    require(input.length == 8704 && length.length == 8, "input byte length");
                    require(ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).getLong() == 17, "true length changed");
                    ByteBuffer features = ByteBuffer.allocateDirect(input.length).order(ByteOrder.LITTLE_ENDIAN);
                    features.put(input).flip();
                    ByteBuffer lengths = ByteBuffer.allocateDirect(length.length).order(ByteOrder.LITTLE_ENDIAN);
                    lengths.put(length).flip();
                    try (OnnxTensor audio = OnnxTensor.createTensor(env, features.asFloatBuffer(), new long[]{1,128,17});
                         OnnxTensor count = OnnxTensor.createTensor(env, lengths.asLongBuffer(), new long[]{1})) {
                        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                        inputs.put("audio_signal", audio); inputs.put("length", count);
                        checkpoint = "run-and-host-output";
                        try (OrtSession.Result outputs = session.run(inputs)) {
                            require(outputs.size() == 2, "expected exactly two outputs");
                            JSONArray tensors = new JSONArray();
                            for (int index = 0; index < 2; index++) {
                                OnnxTensor tensor = (OnnxTensor) outputs.get(index);
                                TensorInfo info = tensor.getInfo();
                                require(info.type == (index == 0 ? OnnxJavaType.FLOAT : OnnxJavaType.INT64), "output dtype: " + info.type);
                                ByteBuffer raw;
                                if (index == 0) {
                                    FloatBuffer values = tensor.getFloatBuffer();
                                    raw = ByteBuffer.allocate(values.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN);
                                    while (values.hasRemaining()) {
                                        float value = values.get();
                                        require(Float.isFinite(value), "nonfinite output");
                                        raw.putFloat(value);
                                    }
                                } else {
                                    LongBuffer values = tensor.getLongBuffer();
                                    raw = ByteBuffer.allocate(values.remaining() * 8).order(ByteOrder.LITTLE_ENDIAN);
                                    while (values.hasRemaining()) {
                                        long value = values.get();
                                        raw.putLong(value);
                                    }
                                }
                                File file = new File(folder, "encoder-probe-output" + index + (index == 0 ? ".f32" : ".i64"));
                                Files.write(file.toPath(), raw.array());
                                tensors.put(new JSONObject().put("file", file.getName()).put("dtype", info.type.toString())
                                    .put("shape", new JSONArray(info.getShape())).put("sha256", sha(file)).put("raw_base64", android.util.Base64.encodeToString(raw.array(), android.util.Base64.NO_WRAP)));
                            }
                            out.put("run_to_host_output_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6).put("outputs", tensors);
                        }
                    }
                    memory.put(resources("after-output"));
                    out.put("profile", session.endProfiling());
                }
            }
            out.put("outcome", "complete").put("checkpoint", "outputs-materialized");
        } catch (Exception | LinkageError error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            out.put("checkpoint", checkpoint).put("failure", trace.toString());
        } finally {
            memory.put(resources("finally"));
            Files.writeString(result.toPath(), out.toString(2));
        }
    }
    private JSONObject resources(String phase) throws Exception {
        Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info);
        long hwm = 0;
        for (String line : Files.readAllLines(new File("/proc/self/status").toPath()))
            if (line.startsWith("VmHWM:")) hwm = Long.parseLong(line.replaceAll("[^0-9]", ""));
        return new JSONObject().put("phase", phase).put("pssKb", info.getTotalPss()).put("highWaterKb", hwm)
            .put("thermalStatus", context.getSystemService(PowerManager.class).getCurrentThermalStatus());
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static String sha(File file) throws Exception { return sha(new FileInputStream(file)); }
    private static String sha(InputStream source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = source) {
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
