package org.lsposed.manager.ui.fragment;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.lsposed.manager.App;
import org.lsposed.manager.adapters.ScopeAdapter;
import org.lsposed.manager.util.ModuleUtil;

import java.util.HashSet;
import java.util.Set;

public class AffectedApp {
    public PackageInfo packageInfo;
    public ScopeAdapter.ApplicationWithEquals application;
    public ApplicationInfo applicationInfo;
    public String packageName;
    public CharSequence label = null;
    public String appName = null;
    public int userId;

    public final Set<ModuleUtil.InstalledModule> modules = new HashSet<>();
    public final Set<ModuleUtil.InstalledModule> recommendModules = new HashSet<>();

    public String getAppName() {
        if (appName == null)
            appName = applicationInfo.loadLabel(App.getInstance().getPackageManager()).toString();
        return appName;
    }

    public void addModule(ModuleUtil.InstalledModule module) {
        synchronized (modules) {
            modules.add(module);
        }
    }
    public void addRecommendModule(ModuleUtil.InstalledModule module) {
        synchronized (recommendModules) {
            recommendModules.add(module);
        }
    }

}
