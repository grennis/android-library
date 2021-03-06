/* Copyright 2018 Urban Airship and Contributors */

package com.urbanairship.iam.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.urbanairship.Logger;
import com.urbanairship.UAirship;
import com.urbanairship.iam.MediaInfo;
import com.urbanairship.js.Whitelist;
import com.urbanairship.messagecenter.ImageLoader;

import java.lang.ref.WeakReference;

/**
 * Media view.
 *
 * @hide
 */
public class MediaView extends FrameLayout {
    private WebView webView;
    private WebChromeClient chromeClient;


    /**
     * Default constructor.
     *
     * @param context A Context object used to access application assets.
     */
    public MediaView(Context context) {
        this(context, null);
    }

    /**
     * Default constructor.
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     */
    public MediaView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Default constructor.
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     */
    public MediaView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Default constructor.
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     * @param defResStyle A resource identifier of a style resource that supplies default values for
     * the view, used only if defStyle is 0 or cannot be found in the theme. Can be 0 to not
     * look for defaults.
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    public MediaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle, int defResStyle) {
        super(context, attrs, defStyle, defResStyle);
    }

    /**
     * Sets the chrome client when loading videos.
     *
     * @param chromeClient The web chrome client.
     */
    public void setChromeClient(WebChromeClient chromeClient) {
        this.chromeClient = chromeClient;
        if (webView != null) {
            webView.setWebChromeClient(chromeClient);
        }
    }

    /**
     * Call during activity pause to pause the media.
     */
    public void onPause() {
        if (this.webView != null) {
            this.webView.onPause();
        }
    }

    /**
     * Call during activity resume to resume the media.
     */
    public void onResume() {
        if (this.webView != null) {
            this.webView.onResume();
        }
    }

    /**
     * Sets the media info.
     *
     * @param mediaInfo The media info.
     * @param cachedMediaUrl The cached media URL.
     */
    public void setMediaInfo(final MediaInfo mediaInfo, final String cachedMediaUrl) {
        removeAllViewsInLayout();

        // If we had a web view previously clear it
        if (this.webView != null) {
            this.webView.stopLoading();
            this.webView.setWebChromeClient(null);
            this.webView.setWebViewClient(null);
            this.webView.destroy();
            this.webView = null;
        }

        switch (mediaInfo.getType()) {
            case MediaInfo.TYPE_IMAGE:
                ImageView imageView = new ImageView(getContext());
                imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                imageView.setAdjustViewBounds(true);
                imageView.setContentDescription(mediaInfo.getDescription());
                addView(imageView);

                String url = cachedMediaUrl == null ? mediaInfo.getUrl() : cachedMediaUrl;
                ImageLoader.shared(getContext()).load(url, 0, imageView);
                break;

            case MediaInfo.TYPE_VIDEO:
                loadWebView(mediaInfo);
                break;

            case MediaInfo.TYPE_YOUTUBE:
                loadWebView(mediaInfo);
                break;
        }
    }

    /**
     * Helper method to load video in the webview.
     *
     * @param mediaInfo The media info.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void loadWebView(@NonNull final MediaInfo mediaInfo) {
        this.webView = new WebView(getContext());

        FrameLayout frameLayout = new FrameLayout(getContext());
        FrameLayout.LayoutParams webViewLayoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webViewLayoutParams.gravity = Gravity.CENTER;

        frameLayout.addView(webView, webViewLayoutParams);

        final ProgressBar progressBar = new ProgressBar(getContext());
        progressBar.setIndeterminate(true);
        progressBar.setId(android.R.id.progress);

        FrameLayout.LayoutParams progressBarLayoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressBarLayoutParams.gravity = Gravity.CENTER;

        frameLayout.addView(progressBar, progressBarLayoutParams);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        }

        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebChromeClient(chromeClient);
        webView.setContentDescription(mediaInfo.getDescription());
        webView.setVisibility(View.INVISIBLE);
        webView.setWebViewClient(new MediaWebViewClient() {
            @Override
            protected void onPageFinished(WebView webView) {
                webView.setVisibility(View.VISIBLE);
                progressBar.setVisibility(GONE);
            }
        });

        if (UAirship.shared().getWhitelist().isWhitelisted(mediaInfo.getUrl(), Whitelist.SCOPE_OPEN_URL)) {
            webView.loadUrl(mediaInfo.getUrl());
        } else {
            Logger.error("URL not whitelisted. Unable to load: " + mediaInfo.getUrl());
        }

        addView(frameLayout);
    }

    private static abstract class MediaWebViewClient extends WebViewClient {
        static final long START_RETRY_DELAY = 1000;

        boolean error = false;
        long retry = START_RETRY_DELAY;

        @Override
        public void onPageFinished(WebView view, final String url) {
            super.onPageFinished(view, url);
            if (error) {
                final WeakReference<WebView> weakReference = new WeakReference<WebView>(view);
                view.getHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        WebView webView = weakReference.get();
                        if (webView != null) {
                            webView.loadUrl(url);
                        }
                    }
                }, retry);
                retry = retry * 2;
            } else {
                onPageFinished(view);
            }

            error = false;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            this.error = true;
        }

        protected abstract void onPageFinished(WebView webView);
    }


}
