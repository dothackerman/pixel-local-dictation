package ch.og.dictation;

import android.content.Context;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;

/** Benchmark-only, short synthetic checkpoint. Never constructs the speech pipeline. */
public final class EncoderProbe {
    private final Context context;
    private final File folder;
    private String checkpoint = "verify";
    private boolean placementListed;
    public EncoderProbe(Context context, File folder) { this.context = context; this.folder = folder; }

    public void run() throws Exception {
        File result = new File(folder, "result.json");
        String attempt = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("shortEncoderAttempt", "");
        require(attempt.matches("[0-9a-f]{32}"), "wrapper-owned short attempt is required");
        require(!result.exists(), "short result already exists; no inference retry");
        JSONObject out = new JSONObject().put("schema", 1).put("protocol", "short-encoder-v1")
            .put("attempt_id", attempt).put("eligible", false)
            .put("scope", "short synthetic feasibility only; full-take checks not run")
            .put("outcome", "failed")
            .put("pid", android.os.Process.myPid()).put("processStartElapsedMs", android.os.Process.getStartElapsedRealtime())
            .put("adapter_driver_proof", "unproven").put("fp32_shader_proof", "unproven")
            .put("residual_node_assignment", "unproven").put("later_checks", "not run");
        JSONArray memory = new JSONArray();
        out.put("memory", memory);
        long initializationStartNs = 0;
        long initializationEndNs = 0;
        long phaseStartNs = 0;
        long runStartNs = 0;
        try {
            File config = new File(folder, "encoder-probe.json");
            JSONObject plan = new JSONObject(Files.readString(config.toPath()));
            out.put("freeze_sha256", sha(config)).put("frozen", plan);
            String provider = plan.getString("provider");
            require(provider.equals("cpu") || provider.equals("webgpu"), "unknown provider " + provider);
            // Placement mode: WebGPU runtime session creation without strict rejection; never Run.
            boolean placement = plan.getString("protocol").equals(PLACEMENT);
            require(!placement || provider.equals("webgpu"), "placement listing requires webgpu");
            if (placement) {
                out.put("protocol", PLACEMENT).put("scope", "placement listing only; Run not executed");
                out.remove("residual_node_assignment");
            }
            // short-encoder-v2: WebGPU under the ADR-023 placement check, then Run.
            boolean v2 = plan.getString("protocol").equals(SHORT_V2);
            boolean listed = placement || (v2 && provider.equals("webgpu"));
            if (v2) out.put("protocol", SHORT_V2);
            // chunk-encoder-v1: Sherpa's encoder session options, N frozen fixtures, one retained session.
            boolean chunk = plan.getString("protocol").equals(CHUNK_V1);
            if (chunk) {
                out.put("protocol", CHUNK_V1).put("scope", "chunk-shape tensor gate only; full-take checks not run");
                out.remove("adapter_driver_proof");
                out.remove("residual_node_assignment");
            }
            require(chunk || (placement || v2 || plan.getString("protocol").equals("short-encoder-v1")) &&
                plan.getInt("true_length") == 17 &&
                plan.getJSONArray("shape").toString().equals("[1,128,17]"), "unsupported frozen shape");
            require(plan.getInt("schema") == 1, "unsupported schema");
            for (String name : new String[]{"encoder.onnx", "encoder.weights"})
                require(sha(new File(folder, "model/" + name)).equals(plan.getJSONObject("model").getString(name)), "model changed: " + name);
            if (chunk) {
                JSONArray fixtures = plan.getJSONArray("fixtures");
                for (int i = 0; i < fixtures.length(); i++) {
                    JSONObject f = fixtures.getJSONObject(i);
                    String name = f.getString("name");
                    require(name.matches("[a-z-]+"), "fixture name");
                    require(sha(new File(folder, "fixtures/" + name + ".f32")).equals(f.getString("features_sha256")), "input changed: " + name);
                    require(sha(new File(folder, "fixtures/" + name + ".i64")).equals(f.getString("length_sha256")), "input changed: " + name);
                }
            } else for (String name : new String[]{"audio_signal.f32", "length.i64"})
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
            initializationStartNs = SystemClock.elapsedRealtimeNanos();
            phaseStartNs = initializationStartNs;
            System.loadLibrary("onnxruntime");
            // Chunk gate uses Sherpa's Env level (ERROR); placement listings need VERBOSE.
            OrtEnvironment env = OrtEnvironment.getEnvironment(chunk ? OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR
                : OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, "encoder-probe");
            require(env.getVersion().equals("1.31.0"), "runtime version mismatch: " + env.getVersion());
            out.put("runtime_version", env.getVersion()).put("available_providers", OrtEnvironment.getAvailableProviders().toString());
            out.put("native_load_ms", (SystemClock.elapsedRealtimeNanos() - phaseStartNs) / 1e6);
            memory.put(resources("before-load"));
            checkpoint = "provider-create";
            phaseStartNs = SystemClock.elapsedRealtimeNanos();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                int intra = 4, inter = 1;
                if (chunk) {
                    // Sherpa session.cc: intra = inter = num_threads, default (ALL) optimization, sequential, fallback allowed.
                    JSONObject session = plan.getJSONObject("session_options");
                    require(session.getString("optimization").equals("ALL_OPT") && session.getString("execution_mode").equals("SEQUENTIAL")
                        && session.getString("cpu_fallback").equals("allowed"), "unsupported chunk session options");
                    intra = session.getInt("intra_op_threads");
                    inter = session.getInt("inter_op_threads");
                }
                options.setIntraOpNumThreads(intra);
                options.setInterOpNumThreads(inter);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (!chunk) {
                    options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
                    options.setSessionLogVerbosityLevel(1);
                }
                options.disableProfiling();
                out.put("optimization", "ALL_OPT").put("execution_mode", "SEQUENTIAL").put("profiling", false)
                    .put("intra_op_threads", intra).put("inter_op_threads", inter)
                    .put("timing_scope", "input preparation, copies, synchronous Run, host outputs, hashes and Base64; diagnostic only");
                if (provider.equals("webgpu")) {
                    if (!listed && !chunk) options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
                    Map<String, String> gpu = new LinkedHashMap<>();
                    gpu.put("enableGraphCapture", "0");
                    gpu.put("enableInt64", "1");
                    gpu.put("preferredLayout", "NCHW");
                    gpu.put("dawnBackendType", "Vulkan");
                    options.addWebGPU(gpu);
                    out.put("provider_options", new JSONObject(gpu)).put("cpu_fallback_disabled", !listed && !chunk);
                } else {
                    options.addCPU(true);
                    out.put("cpu_fallback_disabled", false);
                }
                out.put("provider_create_ms", (SystemClock.elapsedRealtimeNanos() - phaseStartNs) / 1e6);
                // The log reader must be attached before ORT logs placement.
                checkpoint = "placement-read";
                LogTail placementLog = listed ? new LogTail(attempt) : null;
                checkpoint = "session-create";
                phaseStartNs = SystemClock.elapsedRealtimeNanos();
                long start = phaseStartNs;
                try (LogTail tail = placementLog;
                     OrtSession session = env.createSession(new File(folder, "model/encoder.onnx").getAbsolutePath(), options)) {
                    initializationEndNs = SystemClock.elapsedRealtimeNanos();
                    out.put("session_create_ms", (initializationEndNs - phaseStartNs) / 1e6);
                    out.put("cold_load_ms", (initializationEndNs - initializationStartNs) / 1e6);
                    memory.put(resources("after-load"));
                    if (listed) {
                        checkpoint = "placement-read";
                        long readStart = SystemClock.elapsedRealtimeNanos();
                        JSONObject listing = tail.finish();
                        out.put("placement_read_ms", (SystemClock.elapsedRealtimeNanos() - readStart) / 1e6)
                            .put("placement", listing)
                            .put("residual_node_assignment", listing.getBoolean("complete") ? "listed" : "incomplete");
                        if (placement) {
                            placementListed = true;  // Outcome is set only after the runtime session closes.
                            return;
                        }
                        tail.close();  // Stop the log reader so it cannot perturb Run timing.
                        checkpoint = "session-create";
                    }
                    if (chunk) {
                        checkpoint = "gpu-evidence";
                        out.put("gpu_evidence", GpuEvidence.collect(context).put("ep_devices", epDevices(env)));
                        checkpoint = "run-and-host-output";
                        runStartNs = SystemClock.elapsedRealtimeNanos();
                        out.put("fixtures", runFixtures(env, session, plan.getJSONArray("fixtures")));
                        out.put("run_to_host_output_ms", (SystemClock.elapsedRealtimeNanos() - runStartNs) / 1e6);
                        memory.put(resources("after-output"));
                        out.put("outcome", "complete").put("checkpoint", "outputs-materialized");
                        return;  // finally writes the receipt
                    }
                    start = SystemClock.elapsedRealtimeNanos();
                    runStartNs = start;
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
                                require(Arrays.equals(info.getShape(), index == 0 ? new long[]{1,1024,3} : new long[]{1}), "unexpected short output shape");
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
                                        require(value == 3, "unexpected short encoded length");
                                        raw.putLong(value);
                                    }
                                }
                                String label = "encoder-probe-output" + index + (index == 0 ? ".f32" : ".i64");
                                tensors.put(new JSONObject().put("file", label).put("dtype", info.type.toString())
                                    .put("shape", new JSONArray(info.getShape())).put("sha256", sha(new ByteArrayInputStream(raw.array())))
                                    .put("raw_base64", android.util.Base64.encodeToString(raw.array(), android.util.Base64.NO_WRAP)));
                            }
                            out.put("run_to_host_output_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6).put("outputs", tensors);
                        }
                    }
                    memory.put(resources("after-output"));
                }
            }
            out.put("outcome", "complete").put("checkpoint", "outputs-materialized");
        } catch (Exception | LinkageError error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String message = trace.toString();
            if (message.length() > 16384) message = message.substring(0, 16384);
            out.put("checkpoint", checkpoint).put("failure", message);
            android.util.Log.e("LabTest", "short-encoder-v1 " + checkpoint + ": " + message);
        } finally {
            if (placementListed && !out.has("failure"))
                out.put("outcome", "complete").put("checkpoint", "placement-listed");
            long now = SystemClock.elapsedRealtimeNanos();
            if (runStartNs != 0 && !out.has("run_to_host_output_ms"))
                out.put("run_to_failure_ms", (now - runStartNs) / 1e6);
            if (initializationStartNs != 0) {
                long end = initializationEndNs != 0 ? initializationEndNs : now;
                out.put("initialization_elapsed_ms", (end - initializationStartNs) / 1e6)
                    .put("initialization_complete", initializationEndNs != 0);
                if (initializationEndNs == 0 && phaseStartNs != 0)
                    out.put("failed_initialization_phase_ms", (now - phaseStartNs) / 1e6);
            }
            memory.put(resources("finally"));
            byte[] payload = out.toString(2).getBytes(StandardCharsets.UTF_8);
            require(payload.length <= 1024 * 1024, "short receipt exceeds fixed byte bound");
            Files.write(result.toPath(), payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }
    static final String PLACEMENT = "encoder-placement-v1";
    static final String CHUNK_V1 = "chunk-encoder-v1";

    /** Runs every frozen fixture in order through one retained session; raw outputs go to outputs/. */
    private JSONArray runFixtures(OrtEnvironment env, OrtSession session, JSONArray fixtures) throws Exception {
        File outputs = new File(folder, "outputs");
        require(outputs.mkdir(), "outputs already exist; no inference retry");
        JSONArray rows = new JSONArray();
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject f = fixtures.getJSONObject(i);
            String name = f.getString("name");
            int frames = f.getInt("frames");
            byte[] input = Files.readAllBytes(new File(folder, "fixtures/" + name + ".f32").toPath());
            byte[] length = Files.readAllBytes(new File(folder, "fixtures/" + name + ".i64").toPath());
            require(input.length == 128 * frames * 4 && length.length == 8, "fixture byte length: " + name);
            require(ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).getLong() == f.getLong("true_length"), "true length changed: " + name);
            long start = SystemClock.elapsedRealtimeNanos();
            ByteBuffer features = ByteBuffer.allocateDirect(input.length).order(ByteOrder.LITTLE_ENDIAN);
            features.put(input).flip();
            ByteBuffer lengths = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
            lengths.put(length).flip();
            JSONArray tensors = new JSONArray();
            try (OnnxTensor audio = OnnxTensor.createTensor(env, features.asFloatBuffer(), new long[]{1, 128, frames});
                 OnnxTensor count = OnnxTensor.createTensor(env, lengths.asLongBuffer(), new long[]{1})) {
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("audio_signal", audio); inputs.put("length", count);
                try (OrtSession.Result result = session.run(inputs)) {
                    require(result.size() == 2, "expected exactly two outputs");
                    for (int index = 0; index < 2; index++) {
                        OnnxTensor tensor = (OnnxTensor) result.get(index);
                        TensorInfo info = tensor.getInfo();
                        require(info.type == (index == 0 ? OnnxJavaType.FLOAT : OnnxJavaType.INT64), "output dtype: " + info.type);
                        ByteBuffer raw;
                        if (index == 0) {
                            FloatBuffer values = tensor.getFloatBuffer();
                            raw = ByteBuffer.allocate(values.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN);
                            while (values.hasRemaining()) raw.putFloat(values.get());  // Host checks finiteness.
                        } else {
                            LongBuffer values = tensor.getLongBuffer();
                            raw = ByteBuffer.allocate(values.remaining() * 8).order(ByteOrder.LITTLE_ENDIAN);
                            while (values.hasRemaining()) raw.putLong(values.get());
                        }
                        String file = "outputs/" + name + "-" + index + (index == 0 ? ".f32" : ".i64");
                        Files.write(new File(folder, file).toPath(), raw.array(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        tensors.put(new JSONObject().put("file", file).put("dtype", info.type.toString())
                            .put("shape", new JSONArray(info.getShape())).put("sha256", sha(new ByteArrayInputStream(raw.array()))));
                    }
                }
            }
            rows.put(new JSONObject().put("name", name).put("outputs", tensors)
                .put("run_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6));
        }
        return rows;
    }

    /** ORT's own device discovery (vendor/device ids, EP metadata); supplementary to GpuEvidence. */
    private static JSONArray epDevices(OrtEnvironment env) throws JSONException {
        JSONArray rows = new JSONArray();
        try {
            for (OrtEpDevice device : env.getEpDevices()) {
                OrtHardwareDevice hardware = device.getDevice();
                rows.put(new JSONObject().put("ep_name", device.getEpName()).put("ep_vendor", device.getEpVendor())
                    .put("ep_metadata", new JSONObject(device.getEpMetadata()))
                    .put("type", String.valueOf(hardware.getType())).put("vendor", hardware.getVendor())
                    .put("vendor_id", hardware.getVendorId()).put("device_id", hardware.getDeviceId())
                    .put("metadata", new JSONObject(hardware.getMetadata())));
            }
        } catch (Exception error) {
            rows.put(new JSONObject().put("error", String.valueOf(error)));
        }
        return rows;
    }
    static final String SHORT_V2 = "short-encoder-v2";

    /**
     * Streams this process's own ORT log lines (tag "onnxruntime") while the runtime
     * session is created, keeping only placement and placement-cause lines in memory.
     */
    static final class LogTail implements AutoCloseable {
        private static final String VERIFY = "VerifyEachNodeIsAssignedToAnEp] ";
        private final Process process;
        private final Thread reader;
        private final List<String> kept = Collections.synchronizedList(new ArrayList<>());
        private final List<String> other = Collections.synchronizedList(new ArrayList<>());
        private final String begin;
        private final String end;
        private volatile int seen;
        private volatile boolean begun;
        private volatile boolean ended;

        /**
         * Sentinels written under ORT's tag bracket the runtime session creation, so
         * the listing is parsed only after every line logged in between was delivered.
         */
        LogTail(String attempt) throws Exception {
            begin = "placement-begin " + attempt;
            end = "placement-end " + attempt;
            process = new ProcessBuilder("logcat", "-v", "raw", "--pid=" + android.os.Process.myPid(),
                "-T", "1", "onnxruntime:V", "*:S").redirectErrorStream(true).start();
            reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = lines.readLine()) != null; ) {
                        if (line.trim().equals(begin)) { begun = true; continue; }
                        if (line.trim().equals(end)) { ended = true; continue; }
                        if (!begun || ended) continue;
                        seen++;
                        if (line.contains(VERIFY) || line.contains("webgpu kernel not found in registries")
                                || line.contains("Force fallback to CPU execution for node: "))
                            kept.add(line);
                        else if (!line.contains("[") && !line.isBlank() && other.size() < 3)
                            other.add(clip(line));  // logcat/logd errors arrive without ORT's prefix.
                    }
                } catch (IOException ignored) {
                    // Process destroyed; missing sentinels fail the listing.
                }
            }, "encoder-placement-log");
            reader.start();
            try {
                await(begin, () -> begun, 10_000);
            } catch (Exception error) {
                close();
                throw error;
            }
        }

        private void await(String sentinel, java.util.function.BooleanSupplier delivered, long timeoutMs) throws Exception {
            android.util.Log.i("onnxruntime", sentinel);
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!delivered.getAsBoolean()) {
                if (SystemClock.elapsedRealtime() > deadline || !process.isAlive() && !delivered.getAsBoolean())
                    throw new IllegalStateException("own ORT log not delivered (" + sentinel.split(" ")[0]
                        + "); logcat alive=" + process.isAlive() + " lines=" + seen + " other=" + other);
                SystemClock.sleep(100);
            }
        }

        /** Parses once, after the end sentinel proves earlier lines were delivered. */
        JSONObject finish() throws Exception {
            await(end, () -> ended, 20_000);
            return parse();
        }

        /** Kept placement/cause lines, clipped; the host attributes them to sessions. */
        JSONArray finishLines(int limit) throws Exception {
            await(end, () -> ended, 20_000);
            JSONArray lines = new JSONArray();
            synchronized (kept) {
                require(kept.size() <= limit, "placement lines exceed fixed bound: " + kept.size());
                for (String line : kept) lines.put(clip(line));
            }
            return lines;
        }

        int lineCount() { return seen; }

        private JSONObject parse() throws Exception {
            java.util.regex.Pattern header = java.util.regex.Pattern.compile(
                "(All nodes|Node\\(s\\)) placed on \\[([A-Za-z]+ExecutionProvider)\\]\\. Number of nodes: (\\d+)");
            java.util.regex.Pattern node = java.util.regex.Pattern.compile("^\\s+(\\S+) \\((.*)\\)\\s*$");
            java.util.regex.Pattern missing = java.util.regex.Pattern.compile(
                "webgpu kernel not found in registries for Op type: (\\S+) node name: (.*?)\\s*$");
            java.util.regex.Pattern preferred = java.util.regex.Pattern.compile(
                "Force fallback to CPU execution for node: (.*?) because ");
            JSONObject providers = new JSONObject();
            JSONArray kernelNotFound = new JSONArray(), cpuPreferred = new JSONArray();
            JSONArray current = null;
            List<String> snapshot;
            synchronized (kept) { snapshot = new ArrayList<>(kept); }
            for (String line : snapshot) {
                java.util.regex.Matcher m;
                if ((m = missing.matcher(line)).find()) {
                    kernelNotFound.put(new JSONArray().put(clip(m.group(1))).put(clip(m.group(2))));
                } else if ((m = preferred.matcher(line)).find()) {
                    cpuPreferred.put(clip(m.group(1)));
                } else if ((m = header.matcher(line)).find()) {
                    current = new JSONArray();
                    providers.put(m.group(2), new JSONObject().put("declared", Integer.parseInt(m.group(3)))
                        .put("all", m.group(1).equals("All nodes")).put("nodes", current));
                    if (m.group(1).equals("All nodes")) current = null;
                } else if (current != null) {
                    String body = line.substring(line.indexOf(VERIFY) + VERIFY.length());
                    if ((m = node.matcher(body)).find()) current.put(new JSONArray().put(clip(m.group(1))).put(clip(m.group(2))));
                }
            }
            boolean complete = providers.length() > 0;
            for (Iterator<String> it = providers.keys(); it.hasNext(); ) {
                JSONObject row = providers.getJSONObject(it.next());
                complete &= row.getBoolean("all") || row.getJSONArray("nodes").length() == row.getInt("declared");
            }
            return new JSONObject().put("complete", complete).put("providers", providers)
                .put("causes", new JSONObject().put("kernel_not_found", kernelNotFound).put("cpu_preferred", cpuPreferred))
                .put("log_lines", seen);
        }

        private static String clip(String value) { return value.length() <= 512 ? value : value.substring(0, 512); }

        @Override public void close() throws Exception {
            process.destroy();
            reader.join(2_000);
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
