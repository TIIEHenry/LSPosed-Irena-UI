/*
 * <!--This file is part of LSPosed.
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
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.fragment;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.adapters.AffectedAppAdapter;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.adapters.ScopeAdapter;
import org.lsposed.manager.databinding.FragmentAppListBinding;
import org.lsposed.manager.util.ModuleUtil;

import java.util.HashSet;
import java.util.List;

import rikka.material.app.LocaleDelegate;
import rikka.recyclerview.RecyclerViewKt;

public class AppModulesFragment extends BaseFragment implements MenuProvider {
    private static final PackageManager pm = App.getInstance().getPackageManager();

    public SearchView searchView;
    private AffectedAppAdapter scopeAdapter;
    private AffectedApp affectedApp;

    private SearchView.OnQueryTextListener searchListener;
    public FragmentAppListBinding binding;

    private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            if (binding != null && scopeAdapter != null) {
                binding.swipeRefreshLayout.setRefreshing(!scopeAdapter.isLoaded());
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAppListBinding.inflate(getLayoutInflater(), container, false);
        if (affectedApp == null) {
            return binding.getRoot();
        }
        binding.appBar.setLiftable(true);
        String title;
        if (affectedApp.userId != 0) {
            title = String.format(LocaleDelegate.getDefaultLocale(), "%s (%d)", affectedApp.getAppName(), affectedApp.userId);
        } else {
            title = affectedApp.getAppName();
        }
        binding.toolbar.setSubtitle(affectedApp.packageName);

        scopeAdapter = new AffectedAppAdapter(this, affectedApp);
        scopeAdapter.setHasStableIds(true);
        scopeAdapter.registerAdapterDataObserver(observer);
        var concatAdapter = new ConcatAdapter();
        concatAdapter.addAdapter(scopeAdapter.switchAdaptor);
        concatAdapter.addAdapter(scopeAdapter);
        binding.recyclerView.setAdapter(concatAdapter);
        binding.recyclerView.setHasFixedSize(true);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> binding.appBar.setLifted(!top));
        RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
        binding.swipeRefreshLayout.setOnRefreshListener(() -> scopeAdapter.refresh());
        binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());
        Intent intent = AppHelper.getSettingsIntent(affectedApp.packageName, affectedApp.userId);
        if (intent == null) {
            binding.fab.setVisibility(View.GONE);
        } else {
            binding.fab.setVisibility(View.VISIBLE);
            binding.fab.setOnClickListener(v -> ConfigManager.startActivityAsUserWithFeature(intent, affectedApp.userId));
        }
        searchListener = scopeAdapter.getSearchListener();

        setupToolbar(binding.toolbar, binding.clickView, title, R.menu.menu_modules);
        View.OnClickListener l = v -> {
            if (searchView.isIconified()) {
                binding.recyclerView.smoothScrollToPosition(0);
                binding.appBar.setExpanded(true, true);
            }
        };
        binding.toolbar.setOnClickListener(l);
        binding.clickView.setOnClickListener(l);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (affectedApp == null) {
            if (!safeNavigate(R.id.action_app_list_fragment_to_modules_fragment)) {
                safeNavigate(R.id.modules_nav);
            }
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppModulesFragmentArgs args = AppModulesFragmentArgs.fromBundle(getArguments());
        String appPackageName = args.getAppPackageName();
        int appUserId = args.getAppUserId();
        boolean system = "system".equals(appPackageName);
        PackageInfo info;
        try {
            if (system) {
                info = ConfigManager.getPackageInfo("android", 0, appUserId);
            } else {
                info = ConfigManager.getPackageInfo(appPackageName, 0, appUserId);
            }
        } catch (PackageManager.NameNotFoundException e) {
            if (!safeNavigate(R.id.action_app_module_list_fragment_to_app_modules_fragment)) {
                safeNavigate(R.id.modules_nav);
            }
            return;
        }

        ScopeAdapter.ApplicationWithEquals application = new ScopeAdapter.ApplicationWithEquals(appPackageName, appUserId);
        affectedApp = new AffectedApp();
        affectedApp.packageInfo = info;
        affectedApp.label = AppHelper.getAppLabel(info, pm);
        affectedApp.application = application;
        affectedApp.packageName = appPackageName;
        affectedApp.applicationInfo = info.applicationInfo;
        var modules = ModuleUtil.getInstance().getModules();
        if (modules == null) return;
        modules.values().parallelStream().forEach(module -> {
            List<String> scopeList = module.getScopeList();
            if (scopeList != null) {
                if (scopeList.contains(appPackageName)) {
                    affectedApp.addRecommendModule(module);
                } else if (system && scopeList.contains("system")) {
                    affectedApp.addRecommendModule(module);
                }
            }
            var tmpChkList = new HashSet<>(ConfigManager.getModuleScope(module.packageName));
            for (ScopeAdapter.ApplicationWithEquals applicationWithEquals : tmpChkList) {
                if (applicationWithEquals.equals(application)) {
                    affectedApp.addModule(module);
                }
            }
        });
        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                scopeAdapter.onBackPressed();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (scopeAdapter != null) scopeAdapter.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scopeAdapter != null) scopeAdapter.unregisterAdapterDataObserver(observer);
        binding = null;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return false;
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setOnQueryTextListener(searchListener);
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View arg0) {
                binding.appBar.setExpanded(false, true);
                binding.recyclerView.setNestedScrollingEnabled(false);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                binding.recyclerView.setNestedScrollingEnabled(true);
            }
        });
        searchView.findViewById(androidx.appcompat.R.id.search_edit_frame).setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {

    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (scopeAdapter.onContextItemSelected(item)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }
}
