package ch.og.dictation;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;
import com.google.ai.edge.litert.TensorType;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic public-model probe; never opens recordings, preferences or ASR models.
 * Java keeps LiteRT's newer Kotlin metadata out of this project's Kotlin compiler. */
final class AcceleratorAccessProbe {
    private static final String TAG = "AcceleratorProbe";
    private static final String CPU_MODEL = "accelerator/selfie_multiclass_256x256.tflite";
    private static final String TPU_MODEL = "accelerator/selfie_multiclass_256x256_Tensor_G5.tflite";
    private static final String CPU_HASH = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0";
    private static final String TPU_HASH = "4cc1848c7a3a30183a11dc8735b444ba2f7d5afa279cf10de6e3eadd6a3d9cde";
    private final Context target;
    private final Context test = InstrumentationRegistry.getInstrumentation().getContext();
    private final File lab;
    private final JSONObject result = new JSONObject();
    private final JSONArray outcomes = new JSONArray();
    private final AtomicBoolean done = new AtomicBoolean(false);

    AcceleratorAccessProbe(Context target, File lab) {
        this.target = target;
        this.lab = lab;
    }

    void run() throws Exception {
        require(target.getPackageName().equals("ch.og.dictation"), "Wrong target package");
        JSONObject request = new JSONObject(Files.readString(new File(lab, "accelerator-probe.json").toPath()));
        require(request.getString("kind").equals("accelerator-access-v1"), "Unknown probe request");
        result.put("kind", "accelerator-access-v1").put("runtime", "LiteRT 2.3.0")
            .put("syntheticInputs", true).put("performanceClaim", false).put("mode", "opengl-fp32-and-tensor-g5")
            .put("runId", request.getString("runId")).put("outcomes", outcomes);
        checkpoint();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(300_000);
                if (!done.get()) {
                    try {
                        synchronized (result) {
                            result.put("deadlineExceeded", true);
                            checkpoint();
                        }
                    } catch (Exception failure) {
                        Log.e(TAG, "Cannot save deadline checkpoint", failure);
                    } finally {
                        Log.e(TAG, "Probe exceeded 300-second bound; ending own process");
                        Process.killProcess(Process.myPid());
                    }
                }
            } catch (InterruptedException expected) { }
        }, "accelerator-probe-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            List<float[]> baseline = attempt(Accelerator.CPU, CPU_MODEL, CPU_HASH, null);
            attempt(Accelerator.GPU, CPU_MODEL, CPU_HASH, baseline);
            attempt(Accelerator.NPU, TPU_MODEL, TPU_HASH, baseline);
            result.put("complete", true);
        } finally {
            done.set(true);
            watchdog.interrupt();
            checkpoint();
        }
    }

    private List<float[]> attempt(Accelerator accelerator, String asset, String expectedHash,
            List<float[]> baseline) throws Exception {
        JSONObject entry = new JSONObject().put("requestedAccelerators", new JSONArray().put(accelerator.name()))
            .put("effectiveAccelerators", new JSONArray(accelerator == Accelerator.NPU
                ? Arrays.asList("NPU", "CPU") : Collections.singletonList(accelerator.name())))
            .put("modelAsset", asset).put("modelSha256", expectedHash).put("status", "started");
        synchronized (result) { outcomes.put(entry); checkpoint(); }
        Log.i(TAG, "BEGIN " + accelerator.name());
        try {
            try (InputStream input = test.getAssets().open(asset)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = new byte[64 * 1024];
                int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
                require(hex(digest.digest()).equals(expectedHash), "Public model hash mismatch");
            }
            Map<Environment.Option, String> environmentOptions = Collections.emptyMap();
            if (accelerator == Accelerator.NPU) {
                File directory = new File(test.getApplicationInfo().nativeLibraryDir);
                require(new File(directory, "libLiteRtDispatch_GoogleTensor.so").isFile(),
                    "Tensor dispatch library missing from installed nativeLibraryDir");
                environmentOptions = Collections.singletonMap(Environment.Option.DispatchLibraryDir,
                    directory.getAbsolutePath());
                entry.put("dispatchLocation", "installed-test-nativeLibraryDir");
            }
            try (Environment env = Environment.create(environmentOptions)) {
                JSONArray available = new JSONArray();
                for (Accelerator value : env.getAvailableAccelerators()) available.put(value.name());
                entry.put("availableAccelerators", available);
                if (accelerator == Accelerator.GPU) entry.put("glBeforeCompile", glMetadata());
                long started = SystemClock.elapsedRealtimeNanos();
                // 2.3.0 keeps GPU-only strict. Its factory adds CPU to NPU-only options;
                // the pinned G5 graph is dispatch-only and has no CPU implementation.
                CompiledModel.Options options = new CompiledModel.Options(Collections.singleton(accelerator));
                if (accelerator == Accelerator.GPU) {
                    options.setGpuOptions(new CompiledModel.GpuOptions(null, null, null,
                        CompiledModel.GpuOptions.Precision.FP32, null, null, null, null,
                        null, null, null, null, CompiledModel.GpuOptions.Backend.OPENGL, null, null));
                    entry.put("requestedBackend", "OPENGL").put("requestedPrecision", "FP32");
                }
                try (CompiledModel model = CompiledModel.create(test.getAssets(), asset, options, env)) {
                    entry.put("compileMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6);
                    android.opengl.EGLContext compiledContext = null;
                    if (accelerator == Accelerator.GPU) {
                        compiledContext = android.opengl.EGL14.eglGetCurrentContext();
                        entry.put("glAfterCompile", glMetadata());
                    }
                    List<TensorBuffer> inputs = model.createInputBuffers();
                    List<TensorBuffer> outputs = model.createOutputBuffers();
                    try {
                        require(inputs.size() == 1 && outputs.size() == 1, "Unexpected model signature");
                        TensorType inputType = model.getInputTensorType("input_29", "");
                        TensorType outputType = model.getOutputTensorType("Identity", "");
                        require(inputType.getLayout().getDimensions().equals(Arrays.asList(1, 256, 256, 3)), "Wrong input shape");
                        require(outputType.getLayout().getDimensions().equals(Arrays.asList(1, 256, 256, 6)), "Wrong output shape");
                        entry.put("inputType", inputType.toString()).put("outputType", outputType.toString());
                        List<float[]> values = new ArrayList<>();
                        JSONArray samples = new JSONArray();
                        entry.put("samples", samples);
                        for (int pattern = 0; pattern < 2; pattern++) {
                            inputs.get(0).writeFloat(syntheticInput(pattern));
                            long before = SystemClock.elapsedRealtimeNanos();
                            model.run(inputs, outputs);
                            float[] output = outputs.get(0).readFloat();
                            double elapsed = (SystemClock.elapsedRealtimeNanos() - before) / 1e6;
                            require(output.length == 256 * 256 * 6, "Wrong output length");
                            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                            for (float value : output) {
                                require(Float.isFinite(value), "Nonfinite output");
                                min = Math.min(min, value); max = Math.max(max, value);
                            }
                            JSONObject sample = new JSONObject().put("pattern", pattern).put("inferenceMs", elapsed)
                                .put("outputElements", output.length).put("finite", true)
                                .put("outputMin", min).put("outputMax", max).put("outputSha256", floatDigest(output));
                            if (accelerator == Accelerator.GPU) {
                                sample.put("glAfterRun", glMetadata())
                                    .put("contextSameAsAfterCompile", compiledContext.equals(android.opengl.EGL14.eglGetCurrentContext()));
                            }
                            if (baseline != null) sample.put("cpuComparison", compare(baseline.get(pattern), output, accelerator));
                            synchronized (result) { samples.put(sample); checkpoint(); }
                            values.add(output);
                        }
                        double patternDifference = 0;
                        for (int i = 0; i < values.get(0).length; i++) {
                            patternDifference += Math.abs((double) values.get(0)[i] - values.get(1)[i]);
                        }
                        entry.put("betweenPatternOutputL1Difference", patternDifference);
                        require(patternDifference > 0.0, "Distinct synthetic inputs produced identical outputs");
                        entry.put("status", "invocation_succeeded")
                            .put("cpuReferenceAvailable", accelerator == Accelerator.CPU || baseline != null)
                            .put("loadedLibraries", loadedLibraries());
                        return values;
                    } finally {
                        for (TensorBuffer buffer : inputs) buffer.close();
                        for (TensorBuffer buffer : outputs) buffer.close();
                    }
                }
            }
        } catch (Exception | LinkageError failure) {
            entry.put("status", "failed").put("errorType", failure.getClass().getName())
                .put("error", failure.getMessage()).put("causes", causes(failure));
            Log.e(TAG, "FAIL " + accelerator.name() + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            synchronized (result) { checkpoint(); }
            Log.i(TAG, "END " + accelerator.name() + ": " + entry.optString("status"));
        }
        return null;
    }

    private JSONObject compare(float[] cpu, float[] candidate, Accelerator accelerator) throws Exception {
        require(cpu.length == candidate.length, "CPU comparison shape mismatch");
        double max = 0, sum = 0;
        int matching = 0;
        for (int i = 0; i < cpu.length; i++) {
            double difference = Math.abs((double) cpu[i] - candidate[i]);
            max = Math.max(max, difference); sum += difference;
        }
        for (int offset = 0; offset < cpu.length; offset += 6) {
            int cpuClass = 0, candidateClass = 0;
            for (int c = 1; c < 6; c++) {
                if (cpu[offset + c] > cpu[offset + cpuClass]) cpuClass = c;
                if (candidate[offset + c] > candidate[offset + candidateClass]) candidateClass = c;
            }
            if (cpuClass == candidateClass) matching++;
        }
        // Frozen smoke tolerance; does not establish model accuracy/equivalence.
        double meanLimit = accelerator == Accelerator.GPU ? 0.001 : 0.05;
        double maxLimit = accelerator == Accelerator.GPU ? 0.05 : 1.0;
        double agreement = matching / (cpu.length / 6.0);
        return new JSONObject().put("meanAbsoluteError", sum / cpu.length).put("maxAbsoluteError", max)
            .put("pixelClassAgreement", agreement).put("meanErrorLimit", meanLimit)
            .put("maxErrorLimit", maxLimit).put("classAgreementLimit", 0.99)
            .put("withinSmokeTolerance", sum / cpu.length <= meanLimit && max <= maxLimit && agreement >= 0.99);
    }

    private float[] syntheticInput(int pattern) {
        float[] input = new float[256 * 256 * 3];
        for (int i = 0; i < input.length; i++) {
            int channel = i % 3, x = (i / 3) % 256, y = i / (3 * 256);
            input[i] = pattern == 0 ? ((x + 2 * y + 31 * channel) % 256) / 127.5f - 1f
                : ((x / 16 + y / 16 + channel) % 2 == 0 ? -0.6f : 0.6f);
        }
        return input;
    }

    private JSONArray loadedLibraries() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (String line : Files.readAllLines(new File("/proc/self/maps").toPath())) {
            String name = line.substring(line.lastIndexOf('/') + 1);
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".so") && (lower.contains("litert") || lower.contains("opencl") || lower.contains("edgetpu") || lower.contains("gles") || lower.contains("egl") || lower.contains("powervr") || lower.contains("swiftshader") || lower.contains("llvmpipe"))) names.add(name);
        }
        return new JSONArray(names);
    }

    private JSONObject glMetadata() throws Exception {
        android.opengl.EGLContext context = android.opengl.EGL14.eglGetCurrentContext();
        boolean present = !android.opengl.EGL14.EGL_NO_CONTEXT.equals(context);
        JSONObject metadata = new JSONObject().put("currentContextPresent", present);
        if (present) {
            metadata.put("contextIdentity", Long.toUnsignedString(context.getNativeHandle(), 16))
                .put("vendor", JSONObject.wrap(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR)))
                .put("renderer", JSONObject.wrap(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)))
                .put("version", JSONObject.wrap(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION)));
        }
        return metadata;
    }

    private JSONArray causes(Throwable failure) {
        JSONArray causes = new JSONArray();
        Throwable cause = failure.getCause();
        for (int i = 0; i < 5 && cause != null; i++, cause = cause.getCause()) {
            causes.put(cause.getClass().getName() + ": " + cause.getMessage());
        }
        return causes;
    }

    private String floatDigest(float[] values) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
    }

    private String hex(byte[] values) {
        StringBuilder text = new StringBuilder();
        for (byte value : values) text.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return text.toString();
    }

    private void checkpoint() throws Exception {
        File temp = new File(lab, "accelerator-result.tmp");
        Files.writeString(temp.toPath(), result.toString(2), StandardCharsets.UTF_8);
        require(temp.renameTo(new File(lab, "result.json")), "Cannot persist probe checkpoint");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
