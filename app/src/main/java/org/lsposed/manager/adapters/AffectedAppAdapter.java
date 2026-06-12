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

package org.lsposed.manager.adapters;

import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
import static java.util.concurrent.CompletableFuture.runAsync;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.util.Pair;
import androidx.navigation.NavOptions;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.ItemMasterSwitchBinding;
import org.lsposed.manager.databinding.ItemModuleBinding;
import org.lsposed.manager.ui.dialog.BlurBehindDialogBuilder;
import org.lsposed.manager.ui.fragment.AffectedApp;
import org.lsposed.manager.ui.fragment.AppModulesFragment;
import org.lsposed.manager.ui.fragment.CompileDialogFragment;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;
import org.lsposed.manager.util.GlideApp;
import org.lsposed.manager.util.ModuleUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import rikka.core.util.ResourceUtils;
import rikka.widget.mainswitchbar.MainSwitchBar;
import rikka.widget.mainswitchbar.OnMainSwitchChangeListener;

public class AffectedAppAdapter extends EmptyStateRecyclerView.EmptyStateAdapter<AffectedAppAdapter.ViewHolder> implements Filterable {

    private final Activity activity;
    private final AppModulesFragment fragment;
    private final PackageManager pm;
    private final SharedPreferences preferences;
    private final ModuleUtil moduleUtil;

    private final AffectedApp affectedApp;

    private List<ModuleUtil.InstalledModule> searchList = new ArrayList<>();
    private List<ModuleUtil.InstalledModule> showList = new ArrayList<>();

    public RecyclerView.Adapter<RecyclerView.ViewHolder> switchAdaptor = new RecyclerView.Adapter<>() {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(ItemMasterSwitchBinding.inflate(activity.getLayoutInflater(), parent, false).masterSwitch) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            var mainSwitchBar = (MainSwitchBar) holder.itemView;
            mainSwitchBar.setChecked(enabled);
            mainSwitchBar.addOnSwitchChangeListener((switchView, isChecked) -> {
                switchBarOnCheckedChangeListener.onSwitchChanged(switchView, isChecked);
                boolean checked = switchView.isChecked();
                updateTitle(checked, mainSwitchBar);
            });
            updateTitle(enabled, mainSwitchBar);
        }

        private void updateTitle(boolean checked, MainSwitchBar mainSwitchBar) {
            if (checked) {
                mainSwitchBar.setTitle(mainSwitchBar.getResources().getString(R.string.enabled));
            } else {
                mainSwitchBar.setTitle(mainSwitchBar.getResources().getString(R.string.not_enabled));
            }
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    };

    private final OnMainSwitchChangeListener switchBarOnCheckedChangeListener = new OnMainSwitchChangeListener() {
        @Override
        public void onSwitchChanged(Switch view, boolean isChecked) {
            if (enabled == isChecked) {
                return;
            }
            enabled = isChecked;
            if (isChecked) {
                Set<String> lastAppModules = preferences.getStringSet(affectedApp.packageName, new HashSet<>());
                Map<Pair<String, Integer>, ModuleUtil.InstalledModule> utilModules = moduleUtil.getModules();
                if (utilModules == null) {
                    return;
                }
                Set<ModuleUtil.InstalledModule> modules = utilModules.values().stream().filter(module -> lastAppModules.contains(module.packageName)).collect(Collectors.toSet());
                for (ModuleUtil.InstalledModule installedModule : modules) {
                    onCheckedChange(view, isChecked, installedModule);
                }
            } else {
                Set<ModuleUtil.InstalledModule> appliedModules = new HashSet<>(affectedApp.modules);
                Set<String> applied = appliedModules.stream().map(installedModule -> installedModule.packageName).collect(Collectors.toSet());
                preferences.edit().putStringSet(affectedApp.packageName, applied).apply();

                for (ModuleUtil.InstalledModule installedModule : appliedModules) {
                    onCheckedChange(view, isChecked, installedModule);
                }
            }
            fragment.runOnUiThread(AffectedAppAdapter.this::notifyDataSetChanged);
        }
    };

    private ModuleUtil.InstalledModule selectedModule;
    private boolean isLoaded = false;
    private boolean enabled = true;

    public AffectedAppAdapter(AppModulesFragment fragment, AffectedApp affectedApp) {
        this.fragment = fragment;
        this.activity = fragment.requireActivity();
        this.affectedApp = affectedApp;
        moduleUtil = ModuleUtil.getInstance();
        preferences = App.getInstance().getSharedPreferences("app_last_modules", Context.MODE_PRIVATE);
        pm = activity.getPackageManager();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemModuleBinding.inflate(activity.getLayoutInflater(), parent, false));
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setLoaded(List<ModuleUtil.InstalledModule> list, boolean loaded) {
        fragment.runOnUiThread(() -> {
            if (list != null) showList = list;
            isLoaded = loaded;
            notifyDataSetChanged();
        });
    }

    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (selectedModule == null) {
            return false;
        }
        int itemId = item.getItemId();
        if (itemId == R.id.menu_launch) {
            String packageName = selectedModule.packageName;
            if (packageName == null) {
                return false;
            }
            Intent intent = AppHelper.getSettingsIntent(packageName, selectedModule.userId);
            if (intent != null) {
                ConfigManager.startActivityAsUserWithFeature(intent, selectedModule.userId);
            }
            return true;
        } else if (itemId == R.id.menu_other_app) {
            var intent = new Intent(Intent.ACTION_SHOW_APP_INFO);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, selectedModule.packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ConfigManager.startActivityAsUserWithFeature(intent, selectedModule.userId);
            return true;
        } else if (itemId == R.id.menu_app_info) {
            ConfigManager.startActivityAsUserWithFeature(new Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", selectedModule.packageName, null)), selectedModule.userId);
            return true;
        } else if (itemId == R.id.menu_uninstall) {
            new BlurBehindDialogBuilder(activity, R.style.ThemeOverlay_MaterialAlertDialog_FullWidthButtons)
                    .setIcon(selectedModule.app.loadIcon(pm))
                    .setTitle(selectedModule.getAppName())
                    .setMessage(R.string.module_uninstall_message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) ->
                            runAsync(() -> {
                                boolean success = ConfigManager.uninstallPackage(selectedModule.packageName, selectedModule.userId);
                                String text = success ? activity.getString(R.string.module_uninstalled, selectedModule.getAppName()) : activity.getString(R.string.module_uninstall_failed);
                                fragment.showHint(text, false);
                                if (success)
                                    moduleUtil.reloadSingleModule(selectedModule.packageName, selectedModule.userId);
                            }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        } else if (itemId == R.id.menu_repo) {
            var navController = fragment.getNavController();
            navController.navigate(
                    new Uri.Builder().scheme("lsposed").authority("repo").appendQueryParameter("modulePackageName", selectedModule.packageName).build(),
                    new NavOptions.Builder().setEnterAnim(R.anim.fragment_enter).setExitAnim(R.anim.fragment_exit).setPopEnterAnim(R.anim.fragment_enter_pop).setPopExitAnim(R.anim.fragment_exit_pop).setLaunchSingleTop(true).setPopUpTo(fragment.getNavController().getGraph().getStartDestinationId(), false, true).build());
            return true;
        } else if (itemId == R.id.menu_compile_speed) {
            CompileDialogFragment.speed(fragment.getChildFragmentManager(), selectedModule.pkg.applicationInfo);
        }
        return false;
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder.checkbox != null) {
            holder.checkbox.setOnCheckedChangeListener(null);
        }
        super.onViewRecycled(holder);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ModuleUtil.InstalledModule installedModule = showList.get(position);
        String packageName = installedModule.packageName;
        boolean moduleEnabled = moduleUtil.isModuleEnabled(packageName, installedModule.userId);
        holder.root.setAlpha(moduleEnabled && this.enabled ? 1.0f : .5f);
        CharSequence appName = installedModule.getAppName();
        int userId = installedModule.userId;
        holder.appName.setText(appName);
        GlideApp.with(holder.appIcon).load(installedModule.getPackageInfo()).into(new CustomTarget<Drawable>() {
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
        holder.appVersionName.setVisibility(View.VISIBLE);
        holder.appPackageName.setText(packageName);

        holder.appPackageName.setVisibility(View.VISIBLE);
        holder.appVersionName.setText(activity.getString(R.string.app_version, installedModule.getPackageInfo().versionName));
        var sb = new SpannableStringBuilder();
        if (this.affectedApp.recommendModules.contains(installedModule)) {
            String recommended = activity.getString(R.string.requested_by_module);
            sb.append(recommended);
            final ForegroundColorSpan foregroundColorSpan = new ForegroundColorSpan(ResourceUtils.resolveColor(activity.getTheme(), com.google.android.material.R.attr.colorPrimary));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final TypefaceSpan typefaceSpan = new TypefaceSpan(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                sb.setSpan(typefaceSpan, sb.length() - recommended.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            } else {
                final StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
                sb.setSpan(styleSpan, sb.length() - recommended.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }
            sb.setSpan(foregroundColorSpan, sb.length() - recommended.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (!moduleEnabled) {
            if (sb.length() != 0) sb.append("\n");
            String denylist = activity.getString(R.string.not_enabled);
            sb.append(denylist);
            final ForegroundColorSpan foregroundColorSpan = new ForegroundColorSpan(ResourceUtils.resolveColor(activity.getTheme(), com.google.android.material.R.attr.colorError));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final TypefaceSpan typefaceSpan = new TypefaceSpan(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                sb.setSpan(typefaceSpan, sb.length() - denylist.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            } else {
                final StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
                sb.setSpan(styleSpan, sb.length() - denylist.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }
            sb.setSpan(foregroundColorSpan, sb.length() - denylist.length(), sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (sb.length() == 0) {
            holder.hint.setVisibility(View.GONE);
        } else {
            holder.hint.setText(sb);
            holder.hint.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
            activity.getMenuInflater().inflate(R.menu.menu_app_item, menu);
            menu.setHeaderTitle(appName);
            Intent launchIntent = AppHelper.getLaunchIntentForPackage(packageName, userId);
            if (launchIntent == null) {
                menu.removeItem(R.id.menu_launch);
            }
        });

        holder.checkbox.setChecked(affectedApp.modules.contains(installedModule));

        holder.checkbox.setOnCheckedChangeListener((v, isChecked) -> onCheckedChange(v, isChecked, installedModule));

        holder.appIcon.setOnClickListener(v -> {
            fragment.getNavController().navigate(
                    new Uri.Builder().scheme("lsposed").authority("module").appendQueryParameter("modulePackageName", packageName).appendQueryParameter("moduleUserId", String.valueOf(installedModule.userId)).build(),
                    new NavOptions.Builder().setEnterAnim(R.anim.fragment_enter).setExitAnim(R.anim.fragment_exit).setPopEnterAnim(R.anim.fragment_enter_pop).setPopExitAnim(R.anim.fragment_exit_pop).setLaunchSingleTop(true)
                            .setPopUpTo(R.id.app_modules_fragment, false, true).build());
        });
        holder.itemView.setOnClickListener(v -> {
            holder.checkbox.toggle();
            enabled = !affectedApp.modules.isEmpty();
            switchAdaptor.notifyDataSetChanged();
        });
        holder.itemView.setOnLongClickListener(v -> {
            fragment.searchView.clearFocus();
            selectedModule = installedModule;
            return false;
        });
    }

    @Override
    public long getItemId(int position) {
        ModuleUtil.InstalledModule installedModule = showList.get(position);
        return (installedModule.packageName + "!" + installedModule.userId).hashCode();
    }

    @Override
    public Filter getFilter() {
        return new ApplicationFilter();
    }

    @Override
    public int getItemCount() {
        return showList.size();
    }

    public void refresh() {
        var modules = ModuleUtil.getInstance().getModules();
        if (modules == null) {
            return;
        }
        setLoaded(null, false);
        enabled = !affectedApp.modules.isEmpty();
        fragment.runAsync(() -> {
            List<ModuleUtil.InstalledModule> appliedModules = affectedApp.modules.stream().sorted(Comparator.comparing(ModuleUtil.InstalledModule::getAppName)).collect(Collectors.toList());
            List<ModuleUtil.InstalledModule> recommendedModules = affectedApp.recommendModules.stream().filter(installedModule -> !appliedModules.contains(installedModule)).sorted(Comparator.comparing(ModuleUtil.InstalledModule::getAppName)).collect(Collectors.toList());
            List<ModuleUtil.InstalledModule> otherModules = modules.values().stream().sorted(Comparator.comparing(ModuleUtil.InstalledModule::getAppName)).collect(Collectors.toList());
            otherModules.removeAll(appliedModules);
            otherModules.removeAll(recommendedModules);
            showList.clear();
            showList.addAll(appliedModules);
            showList.addAll(recommendedModules);
            showList.addAll(otherModules);
            searchList = new ArrayList<>(showList);

            String queryStr = fragment.searchView != null ? fragment.searchView.getQuery().toString() : "";

            fragment.runOnUiThread(() -> getFilter().filter(queryStr));
        });
    }

    protected void onCheckedChange(CompoundButton buttonView, boolean isChecked, ModuleUtil.InstalledModule installedModule) {
        var tmpChkList = new HashSet<>(ConfigManager.getModuleScope(installedModule.packageName));
        if (isChecked) {
            tmpChkList.add(affectedApp.application);
            affectedApp.addModule(installedModule);
        } else {
            tmpChkList.remove(affectedApp.application);
            affectedApp.modules.remove(installedModule);
        }
        if (!ConfigManager.setModuleScope(installedModule.packageName, installedModule.legacy, tmpChkList)) {
            fragment.showHint(R.string.failed_to_save_scope_list, true);
            if (!isChecked) {
                tmpChkList.add(affectedApp.application);
                affectedApp.addModule(installedModule);
            } else {
                tmpChkList.remove(affectedApp.application);
                affectedApp.modules.remove(installedModule);
            }
            buttonView.setChecked(!isChecked);
        }
    }

    @Override
    public boolean isLoaded() {
        return isLoaded;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ConstraintLayout root;
        ImageView appIcon;
        TextView appName;
        TextView appPackageName;
        TextView appVersionName;
        TextView hint;
        MaterialCheckBox checkbox;

        ViewHolder(ItemModuleBinding binding) {
            super(binding.getRoot());
            root = binding.itemRoot;
            appIcon = binding.appIcon;
            appName = binding.appName;
            appPackageName = binding.appPackageName;
            appVersionName = binding.appVersionName;
            checkbox = binding.checkbox;
            hint = binding.hint;
            checkbox.setVisibility(View.VISIBLE);
        }
    }

    private class ApplicationFilter extends Filter {

        private boolean lowercaseContains(String s, String filter) {
            return !TextUtils.isEmpty(s) && s.toLowerCase().contains(filter);
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults filterResults = new FilterResults();
            List<ModuleUtil.InstalledModule> filtered = new ArrayList<>();
            String filter = constraint.toString().toLowerCase();
            for (ModuleUtil.InstalledModule info : searchList) {
                if (lowercaseContains(info.getAppName(), filter)
                    || lowercaseContains(info.packageName, filter)) {
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
            setLoaded((List<ModuleUtil.InstalledModule>) results.values, true);
        }
    }

    public SearchView.OnQueryTextListener getSearchListener() {
        return new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                getFilter().filter(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                getFilter().filter(query);
                return true;
            }
        };
    }

    public void onBackPressed() {
        fragment.searchView.clearFocus();
        fragment.navigateUp();
    }
}
