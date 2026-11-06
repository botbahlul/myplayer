package com.app.myplayer;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

public class AspectRatioPlayerView extends PlayerView {

    public AspectRatioPlayerView(Context context) {
        super(context);
    }

    public AspectRatioPlayerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioPlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 9f / 16f);
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        );
    }
}
