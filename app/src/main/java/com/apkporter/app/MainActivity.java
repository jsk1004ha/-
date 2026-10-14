package com.apkporter.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Offline exporter for APKs installed for the active Android user/profile.
 * APK data never leaves the device, and the SAF grants the app access only to
 * the destination(s) explicitly selected by the user.
 */
public final class MainActivity extends Activity {
    private static final int REQ_SINGLE = 71;
    private static final int REQ_BATCH = 72;
    private static final int BG = Color.rgb(10, 13, 25);
    private static final int CARD = Color.rgb(23, 28, 47);
    private static final int TEXT = Color.rgb(243, 246, 255);
    private static final int MUTED = Color.rgb(151, 163, 190);
    private static final int ACCENT = Color.rgb(109, 135, 255);
    private static final int STROKE = Color.rgb(49, 58, 82);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<AppEntry> apps = new ArrayList<>();
    private final ArrayList<AppEntry> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final ArrayList<AppEntry> pending = new ArrayList<>();

    private PackageManager pm;
    private AppAdapter adapter;
    private EditText searchInput;
    private Switch systemSwitch;
    private TextView countView;
    private TextView selectedView;
    private TextView batchButton;
    private TextView progressText;
    private FrameLayout busyOverlay;
    private boolean includeSystem = false;
    private boolean exporting = false;

    private static final class AppEntry {
        final ApplicationInfo info;
        final String name;
        final boolean system;
        final int splits;

        AppEntry(ApplicationInfo info, String name) {
            this.info = info;
            this.name = name;
            this.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            this.splits = info.splitSourceDirs == null ? 0 : info.splitSourceDirs.length;
        }

        boolean split() { return splits > 0; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadApps();
    }

    @Override public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v + 0.5f); }

    private GradientDrawable bg(int color, float radius, int borderColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (borderColor != 0) d.setStroke(dp(1), borderColor);
        return d;
    }

    private TextView txt(String s, float sp, int color, boolean heavy) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (heavy) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setIncludeFontPadding(false);
        return v;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(BG);
        LinearLayout page = column();
        screen.addView(page, new FrameLayout.LayoutParams(-1, -1));

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            screen.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        } else {
            screen.setFitsSystemWindows(true);
        }

        LinearLayout heading = column();
        heading.setPadding(dp(22), dp(22), dp(22), dp(10));
        page.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout logoLine = row();
        heading.addView(logoLine);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logoLine.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView brand = txt("  APK PORTER", 12, ACCENT, true);
        brand.setLetterSpacing(0.14f);
        logoLine.addView(brand);

        TextView title = txt("설치된 앱 추출하기", 27, TEXT, true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = dp(14);
        heading.addView(title, titleP);
        TextView subtitle = txt("원하는 앱을 선택해 설치 파일로 저장하세요", 13, MUTED, false);
        LinearLayout.LayoutParams subtitleP = new LinearLayout.LayoutParams(-1, -2);
        subtitleP.topMargin = dp(8);
        heading.addView(subtitle, subtitleP);

        LinearLayout searchCard = row();
        searchCard.setBackground(bg(CARD, 15, STROKE));
        searchCard.setPadding(dp(15), 0, dp(14), 0);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(-1, dp(52));
        searchP.topMargin = dp(22);
        heading.addView(searchCard, searchP);
        TextView searchSymbol = txt("⌕", 27, MUTED, false);
        searchCard.addView(searchSymbol, new LinearLayout.LayoutParams(dp(29), -2));
        searchInput = new EditText(this);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setHint("앱 이름 또는 패키지 검색");
        searchInput.setHintTextColor(MUTED);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(TEXT);
        searchInput.setTextSize(14);
        searchInput.setPadding(dp(5), 0, 0, 0);
        searchCard.addView(searchInput, new LinearLayout.LayoutParams(0, -1, 1));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable e) { }
        });

        LinearLayout control = row();
        LinearLayout.LayoutParams controlP = new LinearLayout.LayoutParams(-1, -2);
        controlP.topMargin = dp(16);
        heading.addView(control, controlP);
        countView = txt("앱 불러오는 중…", 13, TEXT, true);
        control.addView(countView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sysText = txt("시스템 앱", 12, MUTED, false);
        control.addView(sysText);
        systemSwitch = new Switch(this);
        systemSwitch.setChecked(false);
        LinearLayout.LayoutParams switchP = new LinearLayout.LayoutParams(-2, -2);
        switchP.leftMargin = dp(5);
        control.addView(systemSwitch, switchP);
        systemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            includeSystem = isChecked;
            refresh();
        });

        FrameLayout body = new FrameLayout(this);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this);
        list.setPadding(dp(15), dp(1), dp(15), dp(6));
        list.setClipToPadding(false);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(android.R.color.transparent);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = txt("표시할 앱이 없습니다.\n검색어를 바꿔보세요.", 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);

        LinearLayout footer = column();
        footer.setPadding(dp(20), dp(12), dp(20), dp(20));
        footer.setBackground(bg(Color.rgb(16, 20, 35), 0, 0));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tools = row();
        footer.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        selectedView = txt("선택 0개", 13, MUTED, true);
        tools.addView(selectedView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView selectAll = txt("모두 선택", 13, ACCENT, true);
        selectAll.setPadding(dp(8), dp(6), dp(8), dp(6));
        selectAll.setOnClickListener(v -> {
            if (exporting) return;
            for (AppEntry item : shown) selected.add(item.info.packageName);
            updateSelection();
        });
        tools.addView(selectAll);
        TextView clear = txt("선택 해제", 13, MUTED, false);
        clear.setPadding(dp(7), dp(6), dp(4), dp(6));
        clear.setOnClickListener(v -> {
            if (exporting) return;
            selected.clear();
            updateSelection();
        });
        tools.addView(clear);

        batchButton = txt("선택한 앱 내보내기", 16, Color.WHITE, true);
        batchButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams exportP = new LinearLayout.LayoutParams(-1, dp(53));
        exportP.topMargin = dp(12);
        footer.addView(batchButton, exportP);
        batchButton.setOnClickListener(v -> beginBatch());
        TextView note = txt("앱 누르기: 개별 저장  ·  길게 누르기: 다중 선택", 11, MUTED, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteP = new LinearLayout.LayoutParams(-1, -2);
        noteP.topMargin = dp(11);
        footer.addView(note, noteP);

        busyOverlay = new FrameLayout(this);
        busyOverlay.setBackgroundColor(Color.argb(226, 8, 11, 22));
        busyOverlay.setClickable(true);
        LinearLayout progressCard = column();
        progressCard.setGravity(Gravity.CENTER);
        progressCard.setPadding(dp(28), dp(26), dp(28), dp(26));
        progressCard.setBackground(bg(CARD, 22, STROKE));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progressCard.addView(progress, new LinearLayout.LayoutParams(dp(48), dp(48)));
        progressText = txt("파일을 저장하는 중…", 14, TEXT, true);
        progressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressP = new LinearLayout.LayoutParams(-2, -2);
        progressP.topMargin = dp(20);
        progressCard.addView(progressText, progressP);
        FrameLayout.LayoutParams cardP = new FrameLayout.LayoutParams(dp(275), -2, Gravity.CENTER);
        busyOverlay.addView(progressCard, cardP);
        busyOverlay.setVisibility(View.GONE);
        screen.addView(busyOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        updateSelection();
    }

    private void loadApps() {
        executor.execute(() -> {
            ArrayList<AppEntry> loaded = new ArrayList<>();
            try {
                for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                    if (a.sourceDir == null || a.sourceDir.isEmpty()) continue;
                    CharSequence label = a.loadLabel(pm);
                    loaded.add(new AppEntry(a, label == null ? a.packageName : label.toString()));
                }
                Collections.sort(loaded, (a, b) -> {
                    int c = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
                    return c == 0 ? a.info.packageName.compareTo(b.info.packageName) : c;
                });
                runOnUiThread(() -> {
                    apps.clear();
                    apps.addAll(loaded);
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    countView.setText("앱을 불러오지 못했습니다");
                    Toast.makeText(this, "앱 목록 오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void refresh() {
        if (adapter == null || searchInput == null) return;
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppEntry a : apps) {
            if (!includeSystem && a.system) continue;
            if (!query.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(query)
                    && !a.info.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown.add(a);
        }
        countView.setText("앱 " + shown.size() + "개");
        adapter.notifyDataSetChanged();
    }

    private void toggle(AppEntry item) {
        if (exporting) return;
        if (!selected.add(item.info.packageName)) selected.remove(item.info.packageName);
        updateSelection();
    }

    private void updateSelection() {
        if (selectedView == null || batchButton == null) return;
        int n = selected.size();
        selectedView.setText("선택 " + n + "개");
        batchButton.setBackground(bg(n == 0 ? Color.rgb(64, 72, 104) : ACCENT, 15, 0));
        batchButton.setAlpha(n == 0 ? 0.65f : 1f);
        adapter.notifyDataSetChanged();
    }

    private String fileName(AppEntry app) {
        String clean = app.name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty()) clean = app.info.packageName;
        if (clean.length() > 55) clean = clean.substring(0, 55);
        return clean + "_" + app.info.packageName + (app.split() ? ".apks" : ".apk");
    }

    private void beginSingle(AppEntry app) {
        if (exporting) return;
        pending.clear();
        pending.add(app);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(app.split() ? "application/octet-stream" : "application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, fileName(app));
        try { startActivityForResult(intent, REQ_SINGLE); }
        catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "파일 저장 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show();
        }
    }

    private void beginBatch() {
        if (exporting || selected.isEmpty()) return;
        pending.clear();
        for (AppEntry a : apps) if (selected.contains(a.info.packageName)) pending.add(a);
        if (pending.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQ_BATCH); }
        catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "폴더 선택 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pending.isEmpty()) return;
        Uri target = data.getData();
        final ArrayList<AppEntry> copy = new ArrayList<>(pending);
        pending.clear();
        if (requestCode == REQ_SINGLE) {
            setBusy("파일을 저장하는 중…");
            executor.execute(() -> {
                String error = null;
                try {
                    exportApp(copy.get(0), target);
                } catch (Exception e) {
                    error = userError(e);
                    try { DocumentsContract.deleteDocument(getContentResolver(), target); }
                    catch (Exception ignored) { }
                }
                final String finalError = error;
                runOnUiThread(() -> {
                    endBusy();
                    if (finalError == null) Toast.makeText(this, "APK 추출 완료", Toast.LENGTH_LONG).show();
                    else showError("추출 실패: " + finalError);
                });
            });
        } else if (requestCode == REQ_BATCH) {
            setBusy("내보내는 중 (0/" + copy.size() + ")");
            executor.execute(() -> {
                int success = 0;
                String firstError = null;
                ContentResolver resolver = getContentResolver();
                Uri parent;
                try {
                    parent = DocumentsContract.buildDocumentUriUsingTree(target,
                            DocumentsContract.getTreeDocumentId(target));
                } catch (Exception e) {
                    String err = userError(e);
                    runOnUiThread(() -> { endBusy(); showError("폴더를 열 수 없습니다: " + err); });
                    return;
                }
                for (int i = 0; i < copy.size(); i++) {
                    AppEntry app = copy.get(i);
                    Uri result = null;
                    try {
                        result = DocumentsContract.createDocument(resolver, parent,
                                "application/octet-stream", fileName(app));
                        if (result == null) throw new IOException("파일 생성 실패");
                        exportApp(app, result);
                        success++;
                    } catch (Exception e) {
                        if (firstError == null) firstError = app.name + ": " + userError(e);
                        if (result != null) {
                            try { DocumentsContract.deleteDocument(resolver, result); }
                            catch (Exception ignored) { }
                        }
                    }
                    final int done = i + 1;
                    runOnUiThread(() -> progressText.setText("내보내는 중 (" + done + "/" + copy.size() + ")"));
                }
                final int count = success;
                final String failure = firstError;
                runOnUiThread(() -> {
                    endBusy();
                    selected.clear();
                    updateSelection();
                    if (failure == null) Toast.makeText(this, count + "개 APK 추출 완료", Toast.LENGTH_LONG).show();
                    else showError(count + "개 성공, " + (copy.size() - count) + "개 실패\n첫 오류: " + failure);
                });
            });
        }
    }

    private static String userError(Exception error) {
        String s = error.getMessage();
        if (s == null || s.trim().isEmpty()) s = error.getClass().getSimpleName();
        return s.length() > 170 ? s.substring(0, 170) : s;
    }

    private void showError(String message) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("내보내기 결과")
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
    }

    private void setBusy(String message) {
        exporting = true;
        progressText.setText(message);
        busyOverlay.setVisibility(View.VISIBLE);
    }

    private void endBusy() {
        busyOverlay.setVisibility(View.GONE);
        exporting = false;
    }

    /** Stream bytes rather than loading an entire APK into Java heap. */
    private void exportApp(AppEntry app, Uri destination) throws Exception {
        ContentResolver resolver = getContentResolver();
        if (!app.split()) {
            try (OutputStream out = new BufferedOutputStream(requiredOutput(resolver, destination))) {
                copyFile(new File(app.info.sourceDir), out);
            }
            return;
        }
        List<File> sourceFiles = new ArrayList<>();
        sourceFiles.add(new File(app.info.sourceDir));
        for (String p : app.info.splitSourceDirs) sourceFiles.add(new File(p));
        // Prevent creating an apparently valid archive if one of its APKs is inaccessible.
        long total = 0;
        for (File f : sourceFiles) {
            if (!f.isFile() || !f.canRead()) throw new IOException("APK 접근 불가: " + f.getName());
            total += f.length();
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(requiredOutput(resolver, destination)))) {
            zip.setLevel(1); // APKs are already compressed; minimize CPU usage.
            Set<String> usedNames = new HashSet<>();
            for (int i = 0; i < sourceFiles.size(); i++) {
                File file = sourceFiles.get(i);
                String name = i == 0 ? "base.apk" : safeZipName(file.getName(), i);
                if (!usedNames.add(name)) name = "split_" + i + ".apk";
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(file.lastModified());
                zip.putNextEntry(entry);
                copyFile(file, zip);
                zip.closeEntry();
            }

            PackageInfo pkg = pm.getPackageInfo(app.info.packageName, 0);
            long versionCode = Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
            long timestamp = System.currentTimeMillis();
            JSONObject v1 = new JSONObject();
            v1.put("package", app.info.packageName);
            v1.put("label", app.name);
            v1.put("version_code", versionCode);
            v1.put("version_name", pkg.versionName == null ? "" : pkg.versionName);
            v1.put("export_timestamp", timestamp);
            writeZipBytes(zip, "meta.sai_v1.json", v1.toString().getBytes(StandardCharsets.UTF_8));

            JSONObject v2 = new JSONObject(v1.toString());
            v2.put("split_apk", true);
            v2.put("meta_version", 2);
            v2.put("min_sdk", app.info.minSdkVersion);
            v2.put("target_sdk", app.info.targetSdkVersion);
            JSONArray components = new JSONArray();
            JSONObject apkFiles = new JSONObject();
            apkFiles.put("type", "apk_files");
            apkFiles.put("size", total);
            components.put(apkFiles);
            v2.put("backup_components", components);
            writeZipBytes(zip, "meta.sai_v2.json", v2.toString().getBytes(StandardCharsets.UTF_8));

            try {
                Drawable d = pm.getApplicationIcon(app.info);
                Bitmap bitmap = Bitmap.createBitmap(dp(96), dp(96), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                d.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                d.draw(canvas);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                writeZipBytes(zip, "icon.png", bytes.toByteArray());
                bitmap.recycle();
            } catch (Exception ignored) { /* Metadata icon is optional. */ }
        }
    }

    private static String safeZipName(String name, int index) {
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (!safe.endsWith(".apk") || safe.equals("base.apk")) safe = "split_" + index + ".apk";
        return safe;
    }

    private static OutputStream requiredOutput(ContentResolver resolver, Uri uri) throws IOException {
        OutputStream out = resolver.openOutputStream(uri, "w");
        if (out == null) throw new IOException("대상 파일을 열 수 없습니다");
        return out;
    }

    private static void copyFile(File f, OutputStream output) throws IOException {
        if (!f.isFile() || !f.canRead()) throw new IOException("APK 접근 불가: " + f.getName());
        byte[] buffer = new byte[128 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(f))) {
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        }
    }

    private static void writeZipBytes(ZipOutputStream stream, String path, byte[] data) throws IOException {
        stream.putNextEntry(new ZipEntry(path));
        stream.write(data);
        stream.closeEntry();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return shown.get(position).info.packageName.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry app = shown.get(position);
            LinearLayout outer = column();
            outer.setPadding(dp(2), dp(4), dp(2), dp(4));
            LinearLayout item = row();
            boolean checked = selected.contains(app.info.packageName);
            item.setBackground(bg(checked ? Color.rgb(34, 44, 79) : CARD, 16,
                    checked ? ACCENT : STROKE));
            item.setPadding(dp(12), dp(13), dp(12), dp(13));
            outer.addView(item, new LinearLayout.LayoutParams(-1, -2));

            ImageView icon = new ImageView(MainActivity.this);
            try { icon.setImageDrawable(pm.getApplicationIcon(app.info)); }
            catch (Exception e) { icon.setImageResource(R.drawable.ic_launcher); }
            item.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));

            LinearLayout lines = column();
            LinearLayout.LayoutParams linesP = new LinearLayout.LayoutParams(0, -2, 1);
            linesP.leftMargin = dp(13);
            item.addView(lines, linesP);
            TextView label = txt(app.name, 14, TEXT, true);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lines.addView(label);
            TextView pkg = txt(app.info.packageName, 11, MUTED, false);
            pkg.setSingleLine(true);
            pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(5);
            lines.addView(pkg, p);
            TextView kind = txt(app.split() ? (app.splits + 1) + "개 APK · .apks" : "단일 APK · .apk", 10,
                    app.split() ? Color.rgb(181, 174, 255) : Color.rgb(118, 209, 189), false);
            LinearLayout.LayoutParams k = new LinearLayout.LayoutParams(-1, -2);
            k.topMargin = dp(6);
            lines.addView(kind, k);

            CheckBox check = new CheckBox(MainActivity.this);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            check.setChecked(checked);
            check.setContentDescription(app.name + " 선택");
            check.setOnClickListener(v -> toggle(app));
            item.addView(check, new LinearLayout.LayoutParams(dp(43), dp(43)));
            item.setOnClickListener(v -> {
                if (exporting) return;
                if (selected.isEmpty()) beginSingle(app);
                else toggle(app);
            });
            item.setOnLongClickListener(v -> { toggle(app); return true; });
            return outer;
        }
    }
}