package Telegram.Speed.Hook;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

final class HookResolver {
    static Target resolve(Context context) throws Exception {
        loadNativeLibrary(context);
        ClassLoader loader = context.getClassLoader();
        try (DexKitBridge bridge = DexKitBridge.create(loader, false)) {
            List<MethodData> matches = bridge.findMethod(FindMethod.create()
                    .searchPackages("org.telegram.messenger")
                    .matcher(MethodMatcher.create().paramCount(0).returnType("void")
                            .usingNumbers(131072, 524288, 2097152000L)));
            if (matches.size() != 1) {
                throw new IllegalStateException("updateParams matches=" + matches.size());
            }
            MethodData update = matches.get(0);
            Method updateParams = update.getMethodInstance(loader);
            if (Modifier.isStatic(updateParams.getModifiers())) {
                throw new IllegalStateException("updateParams is static");
            }
            List<Field> parameters = fields(update, loader, FieldUsingType.Write, int.class);
            List<Field> guards = fields(update, loader, FieldUsingType.Read, boolean.class);
            if (parameters.size() != 4 || guards.size() != 2) {
                throw new IllegalStateException("unexpected shape=" + parameters.size() + "/" + guards.size());
            }
            MainHook.log("DISCOVERED " + updateParams + "; parameters=" + parameters);
            Target target = new Target(updateParams, parameters, guards);
            List<MethodData> budgets = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().declaredClass(update.getClassName())
                            .paramCount(0).returnType("void").usingNumbers(16, 2, 1048576)));
            if (budgets.size() > 1) {
                throw new IllegalStateException("ambiguous budget method");
            }
            if (budgets.size() == 1) {
                MethodData budget = budgets.get(0);
                List<Field> writes = fields(budget, loader, FieldUsingType.Write, int.class);
                List<Field> flags = fields(budget, loader, FieldUsingType.Write, boolean.class);
                List<Field> reads = fields(budget, loader, FieldUsingType.Read, int.class);
                List<UsingFieldData> usages = budget.getUsingFields();
                if (usages.size() < 2) {
                    throw new IllegalStateException("budget field accesses missing");
                }
                UsingFieldData last = usages.get(usages.size() - 1);
                Field requests = last.getField().getFieldInstance(loader);
                UsingFieldData chunkUsage = usages.get(usages.size() - 2);
                Field chunk = chunkUsage.getField().getFieldInstance(loader);
                Field connectionCount = null;
                for (Field field : writes) {
                    if (!field.equals(requests)) {
                        connectionCount = field;
                        break;
                    }
                }
                if (writes.size() != 2 || flags.size() != 1 || reads.size() < 2
                        || !(budget.getOpCodes().contains(0x93) || budget.getOpCodes().contains(0xb3))
                        || last.getUsingType() != FieldUsingType.Write
                        || !writes.contains(requests)
                        || connectionCount == null
                        || chunkUsage.getUsingType() != FieldUsingType.Read || !reads.contains(chunk)) {
                    throw new IllegalStateException("unexpected budget method shape");
                }
                requests.setAccessible(true);
                chunk.setAccessible(true);
                connectionCount.setAccessible(true);
                target.updateBudget = budget.getMethodInstance(loader);
                target.budgetEnabled = flags.get(0);
                target.connectionCount = connectionCount;
                target.currentRequests = requests;
                target.currentChunk = chunk;
                MainHook.log("BUDGET_DISCOVERED " + target.updateBudget
                        + "; chunk=" + chunk + "; connections=" + connectionCount
                        + "; requests=" + requests);
            }
            return target;
        }
    }

    private static void loadNativeLibrary(Context context) throws Exception {
        try {
            System.loadLibrary("dexkit");
            return;
        } catch (UnsatisfiedLinkError ignored) {
        }
        String moduleApk = MainHook.modulePath;
        if (moduleApk == null) {
            throw new IllegalStateException("module APK path unavailable");
        }
        String[] supportedAbis = Process.is64Bit()
                ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        String abi = supportedAbis[0];
        String entryName = "lib/" + abi + "/libdexkit.so";
        File output;
        try (ZipFile apk = new ZipFile(moduleApk)) {
            ZipEntry entry = apk.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("native library missing for " + abi);
            }
            output = new File(context.getCodeCacheDir(),
                    "libtg-speed-dexkit-" + abi + "-" + entry.getCrc() + ".so");
            if (!output.exists() || output.length() != entry.getSize()) {
                try (InputStream input = apk.getInputStream(entry);
                     FileOutputStream outputStream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, count);
                    }
                }
                if (!output.setReadable(true, true) || !output.setExecutable(true, true)) {
                    throw new IllegalStateException("native library permissions unavailable");
                }
            }
        }
        System.load(output.getAbsolutePath());
    }

    private static List<Field> fields(MethodData method, ClassLoader loader,
                                      FieldUsingType usageType, Class<?> type) throws Exception {
        Set<Field> result = new LinkedHashSet<>();
        for (UsingFieldData usage : method.getUsingFields()) {
            if (usage.getUsingType() != usageType
                    || !method.getClassName().equals(usage.getField().getClassName())) {
                continue;
            }
            Field field = usage.getField().getFieldInstance(loader);
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                result.add(field);
            }
        }
        return new ArrayList<>(result);
    }

    static final class Target {
        final Method updateParams;
        final List<Field> parameters;
        final List<Field> guards;
        Method updateBudget;
        Field budgetEnabled;
        Field connectionCount;
        Field currentRequests;
        Field currentChunk;

        Target(Method updateParams, List<Field> parameters, List<Field> guards) {
            this.updateParams = updateParams;
            this.parameters = parameters;
            this.guards = guards;
        }
    }
}
