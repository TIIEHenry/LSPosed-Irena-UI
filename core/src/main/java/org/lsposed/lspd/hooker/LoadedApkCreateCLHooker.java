/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import static org.lsposed.lspd.core.ApplicationServiceClient.serviceClient;

import android.annotation.SuppressLint;
import android.app.AppComponentFactory;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;
import org.lsposed.lspd.util.MetaDataReader;
import org.lsposed.lspd.util.Utils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint("BlockedPrivateApi")
public class LoadedApkCreateCLHooker implements XposedInterface.Hooker {
    private final static Field defaultClassLoaderField;

    private final static Set<LoadedApk> loadedApks = ConcurrentHashMap.newKeySet();
    private final static ThreadLocal<PackageLoadParam> packageLoadParam = new ThreadLocal<>();

    static {
        Field field = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                field = LoadedApk.class.getDeclaredField("mDefaultClassLoader");
                field.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        defaultClassLoaderField = field;
    }

    static void addLoadedApk(LoadedApk loadedApk) {
        loadedApks.add(loadedApk);
    }

    static PackageLoadParam getPackageLoadParam() {
        return packageLoadParam.get();
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        LoadedApk loadedApk = (LoadedApk) chain.getThisObject();
        Object result = null;
        boolean proceeded = false;
        boolean proceeding = false;

        if (chain.getArg(0) != null || !loadedApks.contains(loadedApk)) {
            return chain.proceed();
        }

        try {
            Hookers.logD("LoadedApk#createClassLoader starts");

            String packageName = ActivityThread.currentPackageName();
            String processName = ActivityThread.currentProcessName();
            boolean isFirstPackage = packageName != null && processName != null && packageName.equals(loadedApk.getPackageName());
            if (!isFirstPackage) {
                packageName = loadedApk.getPackageName();
                processName = ActivityThread.currentPackageName();
            } else if (packageName.equals("android")) {
                packageName = "system";
            }

            if (!isFirstPackage && !XposedHelpers.getBooleanField(loadedApk, "mIncludeCode")) {
                Object mAppDir = XposedHelpers.getObjectField(loadedApk, "mAppDir");
                Hookers.logD("LoadedApk#<init> mIncludeCode == false: " + mAppDir);
                proceeding = true;
                result = chain.proceed();
                proceeding = false;
                proceeded = true;
                return result;
            }

            var param = new PackageLoadParam(loadedApk, isFirstPackage);
            packageLoadParam.set(param);
            proceeding = true;
            result = chain.proceed();
            proceeding = false;
            proceeded = true;
            packageLoadParam.remove();

            Object mAppDir = XposedHelpers.getObjectField(loadedApk, "mAppDir");
            ClassLoader classLoader = (ClassLoader) XposedHelpers.getObjectField(loadedApk, "mClassLoader");
            Hookers.logD("LoadedApk#createClassLoader ends: " + mAppDir + " -> " + classLoader);

            if (classLoader == null) {
                return result;
            }
            param.setClassLoader(classLoader);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                param.setAppComponentFactory((AppComponentFactory) XposedHelpers.getObjectField(loadedApk, "mAppComponentFactory"));
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                    XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = packageName;
            lpparam.processName = processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = loadedApk.getApplicationInfo();
            lpparam.isFirstApplication = isFirstPackage;

            if (isFirstPackage && XposedInit.getLoadedModules().getOrDefault(packageName, Optional.empty()).isPresent()) {
                hookNewXSP(lpparam);
            }

            Hookers.logD("Call handleLoadedPackage: packageName=" + lpparam.packageName + " processName=" + lpparam.processName + " isFirstPackage=" + isFirstPackage + " classLoader=" + lpparam.classLoader + " appInfo=" + lpparam.appInfo);
            XC_LoadPackage.callAll(lpparam);

            LSPosedContext.callOnPackageReady(param);
        } catch (Throwable t) {
            if (proceeding) {
                throw t;
            }
            Hookers.logE("error when hooking LoadedApk#createClassLoader", t);
            if (!proceeded) {
                return chain.proceed();
            }
        } finally {
            packageLoadParam.remove();
            loadedApks.remove(loadedApk);
        }
        return result;
    }

    static class PackageLoadParam implements XposedModuleInterface.PackageReadyParam {
        private final LoadedApk loadedApk;
        private final boolean isFirstPackage;
        private ClassLoader defaultClassLoader;
        private ClassLoader classLoader;
        private AppComponentFactory appComponentFactory;

        PackageLoadParam(LoadedApk loadedApk, boolean isFirstPackage) {
            this.loadedApk = loadedApk;
            this.isFirstPackage = isFirstPackage;
        }

        void setDefaultClassLoader(ClassLoader defaultClassLoader) {
            this.defaultClassLoader = defaultClassLoader;
        }

        void setClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        void setAppComponentFactory(AppComponentFactory appComponentFactory) {
            this.appComponentFactory = appComponentFactory;
        }

        @NonNull
        @Override
        public String getPackageName() {
            return loadedApk.getPackageName();
        }

        @NonNull
        @Override
        public ApplicationInfo getApplicationInfo() {
            return loadedApk.getApplicationInfo();
        }

        @NonNull
        @Override
        public ClassLoader getDefaultClassLoader() {
            if (defaultClassLoader != null) {
                return defaultClassLoader;
            }
            try {
                ClassLoader defaultClassLoader = defaultClassLoaderField == null ? classLoader : (ClassLoader) defaultClassLoaderField.get(loadedApk);
                if (defaultClassLoader == null) {
                    throw new IllegalStateException("Default ClassLoader is not ready");
                }
                return defaultClassLoader;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @NonNull
        @Override
        public ClassLoader getClassLoader() {
            if (classLoader == null) {
                throw new IllegalStateException("ClassLoader is not ready");
            }
            return classLoader;
        }

        @Override
        public boolean isFirstPackage() {
            return isFirstPackage;
        }

        @NonNull
        @Override
        public AppComponentFactory getAppComponentFactory() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw new UnsupportedOperationException();
            }
            if (appComponentFactory != null) {
                return appComponentFactory;
            }
            return (AppComponentFactory) XposedHelpers.getObjectField(loadedApk, "mAppComponentFactory");
        }
    }

    private static void hookNewXSP(XC_LoadPackage.LoadPackageParam lpparam) {
        int xposedminversion = -1;
        boolean xposedsharedprefs = false;
        try {
            Map<String, Object> metaData = MetaDataReader.getMetaData(new File(lpparam.appInfo.sourceDir));
            Object minVersionRaw = metaData.get("xposedminversion");
            if (minVersionRaw instanceof Integer) {
                xposedminversion = (Integer) minVersionRaw;
            } else if (minVersionRaw instanceof String) {
                xposedminversion = MetaDataReader.extractIntPart((String) minVersionRaw);
            }
            xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
        } catch (NumberFormatException | IOException e) {
            Hookers.logE("ApkParser fails", e);
        }

        if (xposedminversion > 92 || xposedsharedprefs) {
            Utils.logI("New modules detected, hook preferences");
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "checkMode", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (((int) param.args[0] & 1/*Context.MODE_WORLD_READABLE*/) != 0) {
                        param.setThrowable(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getPreferencesDir", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return new File(serviceClient.getPrefsPath(lpparam.packageName));
                }
            });
        }
    }
}
