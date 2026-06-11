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
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import android.annotation.SuppressLint;
import android.app.LoadedApk;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;

import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedInterface;

@SuppressLint("BlockedPrivateApi")
public class LoadedApkCreateAppFactoryHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        var param = LoadedApkCreateCLHooker.getPackageLoadParam();
        if (param == null) {
            return chain.proceed();
        }

        try {
            var loadedApk = (LoadedApk) chain.getThisObject();
            var defaultClassLoader = (ClassLoader) XposedHelpers.getObjectField(loadedApk, "mDefaultClassLoader");
            param.setDefaultClassLoader(defaultClassLoader);
            Hookers.logD("Call onPackageLoaded: packageName=" + param.getPackageName()
                    + " isFirstPackage=" + param.isFirstPackage()
                    + " defaultClassLoader=" + param.getDefaultClassLoader());
            LSPosedContext.callOnPackageLoaded(param);
        } catch (Throwable t) {
            Hookers.logE("error when calling onPackageLoaded", t);
        }
        return chain.proceed();
    }
}
