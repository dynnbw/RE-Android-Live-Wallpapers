package com.reandroid.settings;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.reandroid.utils.IoUtils;
import com.reandroid.wallpaper.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页横幅轮播：banners.json 清单驱动（图片托管在 gh-pages），
 * 自动播放 + 手动滑动 + 页点指示器；触摸暂停；减弱动态时关闭自动播放。
 */
public class BannerCarouselPreference extends Preference {
    private static final String TAG = "BannerCarousel";
    private static final String[] MANIFEST_URLS = {
            // jsDelivr CDN（国内可访问，代理 gh-pages 分支）
            "https://cdn.jsdelivr.net/gh/dynnbw/RE-Android-Live-Wallpapers@gh-pages/banners/banners.json",
            // GitHub Pages 兜底
            "https://dynnbw.github.io/RE-Android-Live-Wallpapers/banners/banners.json"
    };
    private static final long AUTO_SCROLL_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ViewPager2 pager;
    private LinearLayout dots;
    private View itemRoot;
    private List<Banner> banners = new ArrayList<>();
    private boolean fetched;

    public BannerCarouselPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.banner_carousel_item);
        setKey("pref_banner_carousel");
        setSelectable(false);
    }

    public BannerCarouselPreference(Context context, AttributeSet attrs) {
        this(context);
    }

    private static class Banner {
        final String image;
        final String link;
        Banner(String image, String link) {
            this.image = image;
            this.link = link;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        itemRoot = holder.itemView;
        pager = holder.itemView.findViewById(R.id.banner_pager);
        dots = holder.itemView.findViewById(R.id.banner_dots);

        if (!fetched) {
            fetched = true;
            fetchManifest();
        } else if (!banners.isEmpty()) {
            setupPager();
        }
    }

    @Override
    public void onDetached() {
        super.onDetached();
        stopAutoScroll();
    }

    // ── 清单拉取 ──

    private void fetchManifest() {
        new Thread(() -> {
            List<Banner> list = new ArrayList<>();
            for (String manifestUrl : MANIFEST_URLS) {
                try {
                    URL url = new URL(manifestUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(CONNECT_TIMEOUT_MS);
                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        try (InputStream is = conn.getInputStream()) {
                            String json = new String(IoUtils.readAllBytes(is), StandardCharsets.UTF_8);
                            JSONObject root = new JSONObject(json);
                            JSONArray arr = root.optJSONArray("banners");
                            if (arr != null) {
                                String base = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1);
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject b = arr.optJSONObject(i);
                                    if (b == null) continue;
                                    String image = b.optString("image");
                                    // 相对路径按清单所在目录解析
                                    if (!image.startsWith("http")) image = base + image;
                                    list.add(new Banner(image, b.optString("link")));
                                }
                            }
                        }
                    }
                    conn.disconnect();
                    if (!list.isEmpty()) break;
                } catch (Exception e) {
                    Log.w(TAG, "fetch manifest failed: " + manifestUrl, e);
                }
            }
            handler.post(() -> {
                if (list.isEmpty()) {
                    // 拉取失败/无横幅：整个轮播隐藏，不影响网格
                    if (itemRoot != null) itemRoot.setVisibility(View.GONE);
                    return;
                }
                banners = list;
                setupPager();
            });
        }, "banner-fetch").start();
    }

    // ── 页面装配 ──

    private void setupPager() {
        if (pager == null) return;
        pager.setAdapter(new BannerAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
            }
        });
        pager.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopAutoScroll();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    startAutoScroll();
                    break;
            }
            return false;
        });
        buildDots();
        updateDots(0);
        startAutoScroll();
    }

    private class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.PageHolder> {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.banner_page, parent, false);
            return new PageHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            Banner banner = banners.get(position);
            Drawable placeholder = ContextCompat.getDrawable(
                    holder.image.getContext(), R.drawable.bg_banner_placeholder);
            Glide.with(holder.image)
                    .load(banner.image)
                    .apply(new RequestOptions()
                            .placeholder(placeholder)
                            .error(placeholder)
                            .diskCacheStrategy(DiskCacheStrategy.DATA))
                    .into(holder.image);
            holder.itemView.setOnClickListener(v -> {
                if (banner.link != null && !banner.link.isEmpty()) {
                    try {
                        holder.itemView.getContext().startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(banner.link)));
                    } catch (Exception e) {
                        Log.w(TAG, "open banner link failed", e);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return banners.size();
        }

        class PageHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            PageHolder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.banner_image);
            }
        }
    }

    // ── 页点指示器 ──

    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        int size = dpToPx(6);
        for (int i = 0; i < banners.size(); i++) {
            View dot = new View(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(dpToPx(3));
            lp.setMarginEnd(dpToPx(3));
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(getContext(), R.drawable.bg_dot));
            dots.addView(dot);
        }
    }

    private void updateDots(int position) {
        if (dots == null) return;
        int control = ContextCompat.getColor(getContext(), R.color.md_theme_control);
        int inactive = ContextCompat.getColor(getContext(), R.color.md_theme_onSurface);
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            dot.getBackground().setTint(i == position ? control : (inactive & 0x00FFFFFF) | 0x66000000);
        }
    }

    // ── 自动播放 ──

    private void startAutoScroll() {
        stopAutoScroll();
        if (banners.size() < 2 || pager == null) return;
        if (Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1) == 0) {
            return; // 系统减弱动态：不自动播放
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (pager == null) return;
                int next = (pager.getCurrentItem() + 1) % banners.size();
                pager.setCurrentItem(next, true);
                handler.postDelayed(this, AUTO_SCROLL_MS);
            }
        };
        autoScrollTask = task;
        handler.postDelayed(task, AUTO_SCROLL_MS);
    }

    private Runnable autoScrollTask;

    private void stopAutoScroll() {
        if (autoScrollTask != null) {
            handler.removeCallbacks(autoScrollTask);
            autoScrollTask = null;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }
}
