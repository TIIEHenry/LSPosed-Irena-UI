package org.lsposed.lspd.hooker;

import android.util.Log;

import org.lsposed.lspd.impl.LSPosedBridge;

import io.github.libxposed.api.XposedInterface;

public class CrashDumpHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        try {
            var e = (Throwable) chain.getArg(0);
            LSPosedBridge.log("Crash unexpectedly: " + Log.getStackTraceString(e));
        } catch (Throwable ignored) {
        }
        return chain.proceed();
    }
}
