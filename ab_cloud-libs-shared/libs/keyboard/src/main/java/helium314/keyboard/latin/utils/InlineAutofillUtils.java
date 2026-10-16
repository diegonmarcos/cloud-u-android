/*
 * Copyright (C) 2019 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0
 */

package helium314.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.inline.InlineContentView;
import android.widget.inline.InlinePresentationSpec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.autofill.inline.UiVersions;
import androidx.autofill.inline.UiVersions.StylesBuilder;
import androidx.autofill.inline.common.ImageViewStyle;
import androidx.autofill.inline.common.TextViewStyle;
import androidx.autofill.inline.common.ViewStyle;
import androidx.autofill.inline.v1.InlineSuggestionUi;
import androidx.autofill.inline.v1.InlineSuggestionUi.Style;

import java.util.ArrayList;
import java.util.List;

import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;

@SuppressLint("RestrictedApi")
@RequiresApi(api = Build.VERSION_CODES.R)
public class InlineAutofillUtils {

    public static InlineSuggestionsRequest createInlineSuggestionRequest(final Context context) {
        final Colors colors = Settings.getValues().mColors;
        final int chipBgDrawableId = androidx.autofill.R.drawable.autofill_inline_suggestion_chip_background;
        final int chipBgColor = colors.get(ColorType.AUTOFILL_BACKGROUND_CHIP);
        final int chipTextColor = colors.get(ColorType.KEY_TEXT);
        final int chipTextHintColor = colors.get(ColorType.KEY_HINT_TEXT);

        StylesBuilder stylesBuilder = UiVersions.newStylesBuilder();
        Style style = InlineSuggestionUi.newStyleBuilder()
                .setSingleIconChipStyle(
                        new ViewStyle.Builder()
                                .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                .setPadding(0, 0, 0, 0)
                                .build())
                .setChipStyle(
                        new ViewStyle.Builder()
                                .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                .build())
                .setStartIconStyle(new ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                .setTitleStyle(
                        new TextViewStyle.Builder()
                                .setTextColor(chipTextColor)
                                .setTextSize(12)
                                .build())
                .setSubtitleStyle(
                        new TextViewStyle.Builder()
                                .setTextColor(chipTextHintColor)
                                .setTextSize(10)
                                .build())
                .setEndIconStyle(new ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                .build();
        stylesBuilder.addStyle(style);
        Bundle stylesBundle = stylesBuilder.build();

        final int height = context.getResources().getDimensionPixelSize(R.dimen.config_suggestions_strip_height);
        final Size min = new Size(100, height);
        final Size max = new Size(740, height);

        // Three InlinePresentationSpec are required for some password managers
        final ArrayList<InlinePresentationSpec> presentationSpecs = new ArrayList<>();
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());

        return new InlineSuggestionsRequest.Builder(presentationSpecs)
                .setMaxSuggestionCount(6)
                .build();
    }

    public static View createView(final List<InlineSuggestion> inlineSuggestions,
                                  final Context context) {
        LinearLayout container = new LinearLayout(context);
        for (InlineSuggestion inlineSuggestion : inlineSuggestions) {
            inlineSuggestion.inflate(context, new Size(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), context.getMainExecutor(), (view) -> {
                if (view != null)
                    container.addView(view);
            });
        }

        HorizontalScrollView inlineSuggestionView = new HorizontalScrollView(context);
        inlineSuggestionView.setHorizontalScrollBarEnabled(false);
        inlineSuggestionView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        inlineSuggestionView.addView(container);

        FrameLayout scrollableSuggestionsClip = new FrameLayout(context);
        final SurfaceView backgroundView = new SurfaceView(context);
        backgroundView.setZOrderOnTop(true);
        backgroundView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        scrollableSuggestionsClip.addView(backgroundView);
        scrollableSuggestionsClip.addView(inlineSuggestionView);
        clipInlineContentTo(scrollableSuggestionsClip);
        return scrollableSuggestionsClip;
    }

    /**
     * A bare container the strip hosts a view of someone else's in (the clipboard chip, the inline
     * autofill row): the strip is Compose, these are not, and this is the one place that builds
     * the Android container for them.
     */
    public static ViewGroup newHost(final Context context) {
        return new FrameLayout(context);
    }

    /**
     * Puts {@code view} (or nothing, when null) in {@code host}, taking it out of whatever held it
     * before. With {@code fill} it takes the whole host; without, it keeps the size it asks for and
     * sits centred in it, as a suggestion view did in the strip.
     */
    public static void setHostedView(final ViewGroup host, @Nullable final View view, final boolean fill) {
        if (view != null && host.getChildCount() == 1 && host.getChildAt(0) == view) return;
        host.removeAllViews();
        if (view == null) return;
        final ViewParent previous = view.getParent();
        if (previous instanceof ViewGroup) ((ViewGroup) previous).removeView(view);
        final FrameLayout.LayoutParams params;
        if (fill || view.getLayoutParams() == null) {
            params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            params = new FrameLayout.LayoutParams(view.getLayoutParams().width, view.getLayoutParams().height);
            params.gravity = Gravity.CENTER_HORIZONTAL;
        }
        host.addView(view, params);
    }

    /**
     * Keeps the {@link InlineContentView}s inside {@code clip} to where {@code clip} is drawn. Without
     * it the InlineContentViews' surfaces would cover parts of the app, as these surfaces are owned
     * by another process and always appear on top. An example is a scrollable list of items.
     */
    private static void clipInlineContentTo(@NonNull final FrameLayout clip) {
        final Rect parentBounds = new Rect();
        final Rect contentBounds = new Rect();
        final ViewTreeObserver.OnDrawListener onDraw = () -> {
            parentBounds.right = clip.getWidth();
            parentBounds.bottom = clip.getHeight();
            clipDescendantInlineContentViews(clip, clip, parentBounds, contentBounds);
        };
        clip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.getViewTreeObserver().addOnDrawListener(onDraw);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.getViewTreeObserver().removeOnDrawListener(onDraw);
            }
        });
    }

    private static void clipDescendantInlineContentViews(
            @NonNull final FrameLayout clip, @Nullable final View root,
            @NonNull final Rect parentBounds, @NonNull final Rect contentBounds) {
        if (root == null) return;
        if (root instanceof InlineContentView inlineContentView) {
            contentBounds.set(parentBounds);
            clip.offsetRectIntoDescendantCoords(inlineContentView, contentBounds);
            inlineContentView.setClipBounds(contentBounds);
            return;
        }
        if (root instanceof ViewGroup rootGroup) {
            final int childCount = rootGroup.getChildCount();
            for (int i = 0; i < childCount; i++) {
                clipDescendantInlineContentViews(clip, rootGroup.getChildAt(i), parentBounds, contentBounds);
            }
        }
    }
}
