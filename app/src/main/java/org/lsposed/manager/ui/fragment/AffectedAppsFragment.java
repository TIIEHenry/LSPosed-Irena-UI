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

import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.lsposed.lspd.models.UserInfo;
import org.lsposed.manager.App;
import org.lsposed.manager.BuildConfig;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.adapters.ScopeAdapter;
import org.lsposed.manager.databinding.FragmentPagerBinding;
import org.lsposed.manager.databinding.ItemAffectedAppBinding;
import org.lsposed.manager.databinding.SwiperefreshRecyclerviewBinding;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.ui.dialog.BlurBehindDialogBuilder;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;
import org.lsposed.manager.util.GlideApp;
import org.lsposed.manager.util.ModuleUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import rikka.recyclerview.RecyclerViewKt;

public class AffectedAppsFragment extends BaseFragment implements ModuleUtil.ModuleListener, RepoLoader.RepoListener, MenuProvider {
    private static final PackageManager pm = App.getInstance().getPackageManager();
    private static final ModuleUtil moduleUtil = ModuleUtil.getInstance();
    private static final RepoLoader repoLoader = RepoLoader.getInstance();
    protected FragmentPagerBinding binding;
    protected SearchView searchView;
    private SearchView.OnQueryTextListener searchListener;

    SparseArray<ModuleAdapter> adapters = new SparseArray<>();
    PagerAdapter pagerAdapter = null;

    private AffectedApp selectedApp;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        searchListener = new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                forEachAdaptor(adapter -> adapter.getFilter().filter(query));
                return false;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                forEachAdaptor(adapter -> adapter.getFilter().filter(query));
                return false;
            }
        };
    }

    private void forEachAdaptor(Consumer<? super ModuleAdapter> action) {
        var snapshot = adapters;
        for (var i = 0; i < snapshot.size(); ++i) {
            action.accept(snapshot.valueAt(i));
        }
    }

    private void showFab() {
        var layoutParams = binding.fab.getLayoutParams();
        if (layoutParams instanceof CoordinatorLayout.LayoutParams) {
            var coordinatorLayoutBehavior =
                    ((CoordinatorLayout.LayoutParams) layoutParams).getBehavior();
            if (coordinatorLayoutBehavior instanceof HideBottomViewOnScrollBehavior) {
                //noinspection unchecked
                ((HideBottomViewOnScrollBehavior<FloatingActionButton>) coordinatorLayoutBehavior).slideUp(binding.fab);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPagerBinding.inflate(inflater, container, false);
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, getString(R.string.Apps), R.menu.menu_affected_apps, view -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        binding.toolbar.setNavigationIcon(null);
        pagerAdapter = new PagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                showFab();
            }
        });

        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
            if (position < adapters.size()) {
                tab.setText(adapters.valueAt(position).getUser().name);
            }
        }).attach();

        binding.tabLayout.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup vg = (ViewGroup) binding.tabLayout.getChildAt(0);
            int tabLayoutWidth = IntStream.range(0, binding.tabLayout.getTabCount()).map(i -> vg.getChildAt(i).getWidth()).sum();
            if (tabLayoutWidth <= binding.getRoot().getWidth()) {
                binding.tabLayout.setTabMode(TabLayout.MODE_FIXED);
                binding.tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
            }
        });

        binding.fab.setOnClickListener(v -> {
            var bundle = new Bundle();
            var user = adapters.valueAt(binding.viewPager.getCurrentItem()).getUser();
            bundle.putParcelable("userInfo", user);
            var f = new RecyclerViewDialogFragment();
            f.setArguments(bundle);
            f.show(getChildFragmentManager(), "install_to_user" + user.id);
        });

        moduleUtil.addListener(this);
        repoLoader.addListener(this);
        onModulesReloaded();

        return binding.getRoot();
    }

    @Override
    public void onPrepareMenu(Menu menu) {
        searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setOnQueryTextListener(searchListener);
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View arg0) {
                binding.appBar.setExpanded(false, true);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        searchView.findViewById(androidx.appcompat.R.id.search_edit_frame).setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
    }

    @Override
    public boolean onMenuItemSelected(@org.jspecify.annotations.NonNull MenuItem menuItem) {
        return false;
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {

    }

    @Override
    public void onResume() {
        super.onResume();
        forEachAdaptor(ModuleAdapter::refresh);
    }

    @Override
    public void onSingleModuleReloaded(ModuleUtil.InstalledModule module) {
        forEachAdaptor(ModuleAdapter::refresh);
    }

    @Override
    public void onModulesReloaded() {
        var users = moduleUtil.getUsers();
        if (users == null) return;

        runOnUiThread(() -> {
            if (users.size() != 1) {
                binding.viewPager.setUserInputEnabled(true);
                binding.tabLayout.setVisibility(View.VISIBLE);
                binding.fab.show();
            } else {
                binding.viewPager.setUserInputEnabled(false);
                binding.tabLayout.setVisibility(View.GONE);
            }
        });

        var tmp = new SparseArray<ModuleAdapter>(users.size());
        var snapshot = adapters;
        for (var user : users) {
            if (snapshot.indexOfKey(user.id) >= 0) {
                tmp.put(user.id, snapshot.get(user.id));
            } else {
                var adapter = new ModuleAdapter(user);
                adapter.setHasStableIds(true);
                tmp.put(user.id, adapter);
            }
        }
        adapters = tmp;
        forEachAdaptor(ModuleAdapter::refresh);
        runOnUiThread(pagerAdapter::notifyDataSetChanged);
        updateModuleSummary();
    }

    @Override
    public void onRepoLoaded() {
        forEachAdaptor(ModuleAdapter::refresh);
    }

    private int appsCount = -1;

    private void updateModuleSummary() {
        runOnUiThread(() -> {
            if (binding != null) {
                binding.toolbar.setSubtitle(appsCount == -1 ? getString(R.string.loading) : getResources().getQuantityString(R.plurals.affected_apps_count, appsCount, appsCount));
                binding.toolbarLayout.setSubtitle(binding.toolbar.getSubtitle());
            }
        });
    }

    @SuppressLint("WrongConstant")
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (selectedApp == null) {
            return false;
        }
        var info = selectedApp.applicationInfo;
        if (info == null) {
            return false;
        }
        int itemId = item.getItemId();
        if (itemId == R.id.menu_launch) {
            Intent launchIntent = AppHelper.getLaunchIntentForPackage(info.packageName, info.uid / App.PER_USER_RANGE);
            if (launchIntent != null) {
                ConfigManager.startActivityAsUserWithFeature(launchIntent, selectedApp.userId);
            }
        } else if (itemId == R.id.menu_compile_speed) {
            CompileDialogFragment.speed(getChildFragmentManager(), info);
        } else if (itemId == R.id.menu_other_app) {
            var intent = new Intent(Intent.ACTION_SHOW_APP_INFO);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, selectedApp.packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ConfigManager.startActivityAsUserWithFeature(intent, selectedApp.userId);
        } else if (itemId == R.id.menu_app_info) {
            ConfigManager.startActivityAsUserWithFeature(new Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", info.packageName, null)), selectedApp.userId);
        } else if (itemId == R.id.menu_force_stop) {
            if (info.packageName.equals("system")) {
                new BlurBehindDialogBuilder(getActivity(), R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons)
                        .setTitle(R.string.reboot)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> ConfigManager.reboot())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                new BlurBehindDialogBuilder(getActivity(), R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons)
                        .setTitle(R.string.force_stop_dlg_title)
                        .setMessage(R.string.force_stop_dlg_text)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> ConfigManager.forceStopPackage(info.packageName, info.uid / 100000))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        moduleUtil.removeListener(this);
        repoLoader.removeListener(this);
        binding = null;
    }

    public static class ModuleListFragment extends Fragment {
        public SwiperefreshRecyclerviewBinding binding;
        private ModuleAdapter adapter;
        private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                binding.swipeRefreshLayout.setRefreshing(!adapter.isLoaded());
            }
        };

        private final View.OnAttachStateChangeListener searchViewLocker = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                binding.recyclerView.setNestedScrollingEnabled(false);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                binding.recyclerView.setNestedScrollingEnabled(true);
            }
        };

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            AffectedAppsFragment fragment = (AffectedAppsFragment) getParentFragment();
            Bundle arguments = getArguments();
            if (fragment == null || arguments == null) {
                return null;
            }
            int userId = arguments.getInt("user_id");
            binding = SwiperefreshRecyclerviewBinding.inflate(getLayoutInflater(), container, false);
            adapter = fragment.adapters.get(userId);
            binding.recyclerView.setAdapter(adapter);
            binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
            binding.swipeRefreshLayout.setOnRefreshListener(adapter::fullRefresh);
            binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());
            RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
            adapter.registerAdapterDataObserver(observer);
            return binding.getRoot();
        }

        void attachListeners() {
            var parent = getParentFragment();
            if (parent instanceof AffectedAppsFragment moduleFragment) {
                binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> moduleFragment.binding.appBar.setLifted(!top));
                moduleFragment.binding.appBar.setLifted(!binding.recyclerView.getBorderViewDelegate().isShowingTopBorder());
                moduleFragment.searchView.addOnAttachStateChangeListener(searchViewLocker);
                binding.recyclerView.setNestedScrollingEnabled(moduleFragment.searchView.isIconified());
                View.OnClickListener l = v -> {
                    if (moduleFragment.searchView.isIconified()) {
                        binding.recyclerView.smoothScrollToPosition(0);
                        moduleFragment.binding.appBar.setExpanded(true, true);
                    }
                };
                moduleFragment.binding.clickView.setOnClickListener(l);
                moduleFragment.binding.toolbar.setOnClickListener(l);
            }
        }

        void detachListeners() {
            binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener(null);
            var parent = getParentFragment();
            if (parent instanceof AffectedAppsFragment moduleFragment) {
                moduleFragment.searchView.removeOnAttachStateChangeListener(searchViewLocker);
                binding.recyclerView.setNestedScrollingEnabled(true);
            }
        }

        @Override
        public void onStart() {
            super.onStart();
            attachListeners();
        }

        @Override
        public void onResume() {
            super.onResume();
            attachListeners();
        }

        @Override
        public void onDestroyView() {
            adapter.unregisterAdapterDataObserver(observer);
            super.onDestroyView();
        }

        @Override
        public void onPause() {
            super.onPause();
            detachListeners();
        }

        @Override
        public void onStop() {
            super.onStop();
            detachListeners();
        }
    }

    private class PagerAdapter extends FragmentStateAdapter {

        public PagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Bundle bundle = new Bundle();
            bundle.putInt("user_id", adapters.keyAt(position));
            Fragment fragment = new ModuleListFragment();
            fragment.setArguments(bundle);
            return fragment;
        }

        @Override
        public int getItemCount() {
            return adapters.size();
        }

        @Override
        public long getItemId(int position) {
            return adapters.keyAt(position);
        }

        @Override
        public boolean containsItem(long itemId) {
            return adapters.indexOfKey((int) itemId) >= 0;
        }
    }

    ModuleAdapter createPickModuleAdapter(UserInfo userInfo) {
        return new ModuleAdapter(userInfo, true);
    }

    class ModuleAdapter extends EmptyStateRecyclerView.EmptyStateAdapter<ModuleAdapter.ViewHolder> implements Filterable {
        private List<AffectedApp> searchList = new ArrayList<>();
        private List<AffectedApp> showList = new ArrayList<>();
        private UserInfo user;
        private final boolean isPick;
        private boolean isLoaded;
        private View.OnClickListener onPickListener;

        ModuleAdapter(UserInfo user) {
            this(user, false);
        }

        ModuleAdapter(UserInfo user, boolean isPick) {
            this.user = user;
            this.isPick = isPick;
        }

        public UserInfo getUser() {
            return user;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ItemAffectedAppBinding.inflate(getLayoutInflater(), parent, false));
        }

        public boolean isPick() {
            return isPick;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AffectedApp affectedApp = showList.get(position);
            boolean nonSelected = affectedApp.modules.isEmpty();
            boolean anyEnabled = affectedApp.modules.stream().anyMatch(installedModule -> moduleUtil.isModuleEnabled(installedModule.packageName, installedModule.userId));
            holder.itemView.setAlpha(anyEnabled ? 1f : (nonSelected ? .3f : 0.6f));
            String packageName = affectedApp.packageName;
            boolean system = packageName.equals("system");
            CharSequence appName;
            int userId = affectedApp.applicationInfo.uid / App.PER_USER_RANGE;
            Context context = holder.root.getContext();
            appName = system ? context.getString(R.string.android_framework) : affectedApp.label;
            holder.appName.setText(appName);
            GlideApp.with(holder.appIcon).load(affectedApp.packageInfo).into(new CustomTarget<Drawable>() {
                @Override
                public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                    holder.appIcon.setImageDrawable(resource);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {

                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    holder.appIcon.setImageDrawable(pm.getDefaultActivityIcon());
                }
            });
            if (system) {
                //noinspection SetTextI18n
                holder.appPackageName.setText("system");
                holder.appVersion.setVisibility(View.GONE);
            } else {
                holder.appVersion.setVisibility(View.VISIBLE);
                holder.appPackageName.setText(packageName);
            }
            holder.appPackageName.setVisibility(View.VISIBLE);
            holder.appVersion.setText("" + affectedApp.packageInfo.versionName);
            var sb = new SpannableStringBuilder();
            if (sb.length() == 0) {
                holder.hint.setVisibility(View.GONE);
            } else {
                holder.hint.setText(sb);
                holder.hint.setVisibility(View.VISIBLE);
            }

            holder.itemView.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
                getActivity().getMenuInflater().inflate(R.menu.menu_app_item, menu);
                menu.setHeaderTitle(appName);
                Intent launchIntent = AppHelper.getLaunchIntentForPackage(packageName, userId);
                if (launchIntent == null) {
                    menu.removeItem(R.id.menu_launch);
                }
                if (system) {
                    menu.findItem(R.id.menu_force_stop).setTitle(R.string.reboot);
                    menu.removeItem(R.id.menu_compile_speed);
                    menu.removeItem(R.id.menu_other_app);
                    menu.removeItem(R.id.menu_app_info);
                }
            });

            holder.modules.setText("" + affectedApp.modules.size());

            holder.itemView.setOnClickListener(v -> {
                searchView.clearFocus();
                safeNavigate(AffectedAppsFragmentDirections.actionAffectedAppsFragmentToAppModulesFragment(packageName, affectedApp.userId));
            });
            holder.itemView.setOnLongClickListener(v -> {
                searchView.clearFocus();
                selectedApp = affectedApp;
                return false;
            });
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.itemView.setTag(null);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return showList.size();
        }

        @Override
        public long getItemId(int position) {
            var module = showList.get(position);
            return (module.packageName + "!" + module.userId).hashCode();
        }

        @Override
        public Filter getFilter() {
            return new ApplicationFilter();
        }

        public void setOnPickListener(View.OnClickListener onPickListener) {
            this.onPickListener = onPickListener;
        }

        public void refresh() {
            runAsync(reloadModules);
        }

        public void fullRefresh() {
            runAsync(() -> {
                setLoaded(null, false);
                moduleUtil.reloadInstalledModules();
                refresh();
            });
        }

        private final Object lock = new Object();
        private final Runnable reloadModules = () -> {
            var modules = moduleUtil.getModules();
            if (modules == null) return;
            synchronized (lock) {
                Log.i("AffectedAppsFragment", "reloadModules");
                var apps = new HashMap<String, AffectedApp>();
                List<PackageInfo> appList = AppHelper.getAppList(false);
                for (PackageInfo info : appList) {
                    int userId = info.applicationInfo.uid / App.PER_USER_RANGE;
                    String packageName = info.packageName;
                    if (packageName.equals("system") && userId != 0 ||
                        packageName.equals(BuildConfig.APPLICATION_ID)) {
                        continue;
                    }

                    ScopeAdapter.ApplicationWithEquals application = new ScopeAdapter.ApplicationWithEquals(packageName, userId);
                    if (userId != user.id) {
                        continue;
                    }
                    AffectedApp affectedApp = apps.computeIfAbsent(packageName, new Function<String, AffectedApp>() {
                        @Override
                        public AffectedApp apply(String s) {
                            AffectedApp affectedApp = new AffectedApp();
                            affectedApp.packageInfo = info;
                            affectedApp.label = AppHelper.getAppLabel(info, pm);
                            affectedApp.application = application;
                            affectedApp.packageName = packageName;
                            affectedApp.applicationInfo = info.applicationInfo;
                            return affectedApp;
                        }
                    });
                }
                for (ModuleUtil.InstalledModule module : modules.values()) {
                    List<String> scopeList = module.getScopeList();
                    if (scopeList == null) {
                        scopeList = new ArrayList<>();
                    }
                    for (String s : scopeList) {
                        AffectedApp affectedApp = apps.get(s);
                        if (affectedApp != null) {
                            affectedApp.addRecommendModule(module);
                        }
                    }
                    var tmpChkList = new HashSet<>(ConfigManager.getModuleScope(module.packageName));
                    for (ScopeAdapter.ApplicationWithEquals applicationWithEquals : tmpChkList) {
                        if (applicationWithEquals.userId == user.id) {
                            AffectedApp affectedApp = apps.get(applicationWithEquals.packageName);
                            if (affectedApp != null) {
                                affectedApp.addModule(module);
                            }
                        }
                    }
                }
                Comparator<PackageInfo> cmp = AppHelper.getAppListComparator(0, pm);
                setLoaded(null, false);
                List<AffectedApp> affectedAppList = apps.values().stream().filter(new Predicate<AffectedApp>() {
                    @Override
                    public boolean test(AffectedApp appInfo) {
                        return !(appInfo.modules.isEmpty() && appInfo.recommendModules.isEmpty());
                    }
                }).collect(Collectors.toList());
                appsCount = affectedAppList.size();
                updateModuleSummary();

                var tmpList = new ArrayList<AffectedApp>();
                affectedAppList.parallelStream()
                        .sorted(new Comparator<>() {
                            @Override
                            public int compare(AffectedApp o1, AffectedApp o2) {
                                return cmp.compare(o1.packageInfo, o2.packageInfo);
                            }
                        }).forEachOrdered(new Consumer<>() {
                            private final HashSet<String> uniquer = new HashSet<>();

                            @Override
                            public void accept(AffectedApp info) {
                                if (isPick()) {
                                    if (!uniquer.contains(info.packageName)) {
                                        uniquer.add(info.packageName);
                                        if (info.userId != getUser().id)
                                            tmpList.add(info);
                                    }
                                } else if (info.userId == getUser().id) {
                                    tmpList.add(info);
                                }
                            }
                        });
                AffectedApp androidInfo = apps.get("android");
                AffectedApp systemInfo = apps.get("system");
                tmpList.remove(androidInfo);
                tmpList.remove(systemInfo);
                tmpList.add(0, systemInfo);
                if (systemInfo != null && androidInfo != null) {
                    systemInfo.modules.addAll(androidInfo.modules);
                }
                String queryStr = searchView != null ? searchView.getQuery().toString() : "";
                searchList = tmpList;
                runOnUiThread(() -> getFilter().filter(queryStr));
            }
        };

        @SuppressLint("NotifyDataSetChanged")
        private void setLoaded(List<AffectedApp> list, boolean loaded) {
            runOnUiThread(() -> {
                if (list != null) showList = list;
                isLoaded = loaded;
                notifyDataSetChanged();
            });
        }

        @Override
        public boolean isLoaded() {
            return isLoaded && moduleUtil.isModulesLoaded();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ConstraintLayout root;
            ImageView appIcon;
            TextView appName;
            TextView appPackageName;
            TextView appDescription;
            TextView appVersion;
            TextView hint;
            TextView modules;

            ViewHolder(ItemAffectedAppBinding binding) {
                super(binding.getRoot());
                root = binding.itemRoot;
                appIcon = binding.appIcon;
                appName = binding.appName;
                appPackageName = binding.appPackageName;
                appDescription = binding.description;
                appVersion = binding.versionName;
                hint = binding.hint;
                modules = binding.checkbox;
            }
        }

        class ApplicationFilter extends Filter {

            private boolean lowercaseContains(String s, String filter) {
                return !TextUtils.isEmpty(s) && s.toLowerCase().contains(filter);
            }

            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults filterResults = new FilterResults();
                List<AffectedApp> filtered = new ArrayList<>();
                String filter = constraint.toString().toLowerCase();
                lp:
                for (AffectedApp info : searchList) {
                    if (lowercaseContains(info.getAppName(), filter) ||
                        lowercaseContains(info.packageName, filter)) {
                        filtered.add(info);
                    }
                }
                filterResults.values = filtered;
                filterResults.count = filtered.size();
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                //noinspection unchecked
                setLoaded((List<AffectedApp>) results.values, true);
            }
        }
    }
}
