package org.lsposed.lspd.hooker;

import android.app.ActivityThread;

import de.robv.android.xposed.XposedInit;
import io.github.libxposed.api.XposedInterface;

public class AttachHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        var result = chain.proceed();
        XposedInit.loadModules((ActivityThread) chain.getThisObject());
        return result;
    }
}
