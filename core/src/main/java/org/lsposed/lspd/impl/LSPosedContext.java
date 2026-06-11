package org.lsposed.lspd.impl;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.DeadSystemException;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.nativebridge.NativeAPI;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.XposedFrameworkError;


@SuppressLint("NewApi")
public class LSPosedContext implements XposedInterface {

    private static final String TAG = "LSPosedContext";

    public static boolean isSystemServer;
    public static String appDir;
    public static String processName;

    static final Set<XposedModule> modules = ConcurrentHashMap.newKeySet();

    private final String mPackageName;
    private final ApplicationInfo mApplicationInfo;
    private final ILSPInjectedModuleService service;
    private final ExceptionMode mDefaultExceptionMode;
    private final Map<String, SharedPreferences> mRemotePrefs = new ConcurrentHashMap<>();

    LSPosedContext(String packageName, ApplicationInfo applicationInfo, ILSPInjectedModuleService service,
                   ExceptionMode defaultExceptionMode) {
        this.mPackageName = packageName;
        this.mApplicationInfo = applicationInfo;
        this.service = service;
        this.mDefaultExceptionMode = defaultExceptionMode;
    }

    public static void callOnPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnPackageReady(XposedModuleInterface.PackageReadyParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageReady(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageReady of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        for (XposedModule module : modules) {
            try {
                module.onSystemServerStarting(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerStarting of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static boolean loadModule(ActivityThread at, Module module) {
        try {
            Log.d(TAG, "Loading module " + module.packageName);
            var sb = new StringBuilder();
            var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
            }
            var librarySearchPath = sb.toString();
            var initLoader = XposedModule.class.getClassLoader();
            var mcl = LspModuleClassLoader.loadApk(module.apkPath, module.file.preLoadedDexes, librarySearchPath, initLoader);
            if (mcl.loadClass(XposedModule.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + module.packageName);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                return false;
            }
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);
            var defaultExceptionMode = module.file.exceptionPassthrough ? ExceptionMode.PASSTHROUGH : ExceptionMode.PROTECTIVE;
            var ctx = new LSPosedContext(module.packageName, module.applicationInfo, module.service, defaultExceptionMode);
            for (var entry : module.file.moduleClassNames) {
                var moduleClass = mcl.loadClass(entry);
                Log.d(TAG, "  Loading class " + moduleClass);
                if (!XposedModule.class.isAssignableFrom(moduleClass)) {
                    Log.e(TAG, "    This class doesn't implement any sub-interface of XposedModule, skipping it");
                    continue;
                }
                try {
                    var moduleContext = (XposedModule) moduleClass.getConstructor().newInstance();
                    moduleContext.attachFramework(ctx);
                    moduleContext.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                        @Override
                        public boolean isSystemServer() {
                            return isSystemServer;
                        }

                        @NonNull
                        @Override
                        public String getProcessName() {
                            return processName;
                        }
                    });
                    modules.add(moduleContext);
                } catch (Throwable e) {
                    Log.e(TAG, "    Failed to load class " + moduleClass, e);
                }
            }
            Log.d(TAG, "Loaded module " + module.packageName + ": " + ctx);
        } catch (Throwable e) {
            Log.d(TAG, "Loading module " + module.packageName, e);
            return false;
        }
        return true;
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        try {
            return service.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new XposedFrameworkError(e);
        }
    }

    @Override
    @NonNull
    public HookBuilder hook(@NonNull Executable origin) {
        return LSPosedBridge.newHookBuilder(this, origin, mDefaultExceptionMode);
    }

    @Override
    @NonNull
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        return LSPosedBridge.newClassInitializerHookBuilder(this, origin, mDefaultExceptionMode);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return LSPosedBridge.doDeoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return LSPosedBridge.newInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return LSPosedBridge.newInvoker(constructor);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        log(priority, tag, msg, null);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String message, @Nullable Throwable throwable) {
        if (message.isEmpty() && throwable == null) {
            return;
        }

        var estimatedLength = Math.max(0xFC2 - (tag == null ? 0 : tag.length()), 100);
        var output = new StringWriter(estimatedLength);
        var writer = new PrintWriter(output);

        var moduleTag = String.valueOf(tag);
        writer.println(String.format("[%s,%s] %s", mPackageName, moduleTag, message));

        if (throwable != null) {
            Throwable candidate;
            for (candidate = throwable; candidate != null && !(candidate instanceof UnknownHostException); candidate = candidate.getCause()) {
                if (candidate instanceof DeadSystemException) {
                    writer.println("DeadSystemException: The system died; earlier logs will point to the root cause");
                    break;
                }
            }
            if (candidate == null) {
                throwable.printStackTrace(writer);
            }
        }

        writer.flush();
        Log.println(priority, TAG, output.toString());
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        return mRemotePrefs.computeIfAbsent(name, n -> {
            try {
                return new LSPosedRemotePreferences(service, n);
            } catch (RemoteException e) {
                log(Log.ERROR, "null", "Failed to get remote preferences", e);
                throw new XposedFrameworkError(e);
            }
        });
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        try {
            return service.getRemoteFileList();
        } catch (RemoteException e) {
            log(Log.ERROR, "null", "Failed to list remote files", e);
            throw new XposedFrameworkError(e);
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        try {
            return service.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }
}
