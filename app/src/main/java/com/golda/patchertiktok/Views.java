package com.golda.patchertiktok;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Small view lookups shared by the features that add to TikTok's own screens. */
final class Views {
    private static final String RECYCLER = "androidx.recyclerview.widget.RecyclerView";

    private Views() { }

    /** The activity behind a context, through its wrappers. */
    static Activity activity(Context context) {
        for (int depth = 0; context != null && depth < 10; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** TikTok's lists are AndroidX RecyclerViews, often subclassed. */
    static boolean isRecycler(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            if (RECYCLER.equals(type.getName())) return true;
        }
        return false;
    }

    /** The first text view with text, depth first. */
    static TextView firstText(View view) {
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = firstText(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
