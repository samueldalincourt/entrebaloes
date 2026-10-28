/* SPDX-License-Identifier: Apache-2.0 */
package br.com.leitorlocal.tradutor_webtoon.mlc;

import br.com.leitorlocal.tradutor_webtoon.MlcRuntime;
import java.util.function.Consumer;
import org.apache.tvm.Device;
import org.apache.tvm.Function;
import org.apache.tvm.Module;
import org.json.JSONObject;

/** MLC JSON FFI through the real TVM JNI runtime; no network inference. */
public final class NativeMlcRuntime implements MlcRuntime {
    private Module engine;
    private Function callback;
    private Thread worker, streamWorker;
    private Function get(String name) { return engine.getFunction(name); }

    @Override public void initialize(Consumer<String> onChunk) {
        Function create = Function.getFunction("mlc.json_ffi.CreateJSONFFIEngine");
        if (create == null) throw new IllegalStateException("MLC JSON FFI não está compilado no runtime");
        engine = create.invoke().asModule();
        Device device = Device.opencl();
        callback = Function.convertFunc(args -> { onChunk.accept(args[0].asString()); return 1; });
        get("init_background_engine").pushArg(device.deviceType).pushArg(device.deviceId)
            .pushArg(callback).invoke();
        worker = new Thread(() -> runWorker("run_background_loop", onChunk), "mlc-inference");
        streamWorker = new Thread(() -> runWorker("run_background_stream_back_loop", onChunk), "mlc-output");
        worker.start(); streamWorker.start();
    }
    private void runWorker(String name, Consumer<String> onChunk) {
        try { get(name).invoke(); }
        catch (Throwable error) {
            onChunk.accept("{\"error\":" + JSONObject.quote("MLC: " + error.toString()) + "}");
        }
    }
    @Override public void reload(String config) { get("reload").pushArg(config).invoke(); }
    @Override public void generate(String request, String id) { get("chat_completion").pushArg(request).pushArg(id).invoke(); }
    @Override public void abort(String id) { if (engine != null) get("abort").pushArg(id).invoke(); }
    @Override public void close() {
        if (engine == null) return;
        get("unload").invoke();
        get("exit_background_loop").invoke();
        try {
            if (worker != null) worker.join(3000);
            if (streamWorker != null) streamWorker.join(3000);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        // Keep JNI callback alive until both native workers have exited.
        if ((worker == null || !worker.isAlive()) && (streamWorker == null || !streamWorker.isAlive())) {
            callback.release(); engine.release(); engine = null;
        }
    }
}
