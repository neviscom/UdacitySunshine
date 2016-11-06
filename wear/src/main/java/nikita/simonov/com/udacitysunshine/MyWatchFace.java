/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nikita.simonov.com.udacitysunshine;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(10);

    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        private Bitmap mWeatherIcon;
        private int mHighTemp;
        private int mLowTemp;

        private Paint mBackgroundPaint;
        private Paint mHoursPaint;
        private Paint mMinutesPaint;
        private Paint mDatePaint;
        private Paint mDividerPaint;
        private Paint mIconPaint;
        private Paint mHighTempPaint;
        private Paint mLowTempPaint;

        private Calendar mCalendar;

        // graphic objects
        private SimpleDateFormat mDayOfWeekFormat;
        private DateFormat mDateFormat;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        float mXOffset;
        float mYOffset;
        float mDateOffset;
        float mDividerYOffset;
        float mDividerWidth;
        float mForecastYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Typeface lightBold = Typeface.create("sans-serif-light", Typeface.BOLD);
            Typeface lightNormal = Typeface.create("sans-serif-light", Typeface.NORMAL);
            Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);

            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.time_y_offset);
            mDateOffset = resources.getDimension(R.dimen.date_y_offset);
            mDividerYOffset = resources.getDimension(R.dimen.divider_y_offset);
            mForecastYOffset = resources.getDimension(R.dimen.forecast_y_offset);
            mDividerWidth = resources.getDimension(R.dimen.divider_width);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getBaseContext(), R.color.blue));

            mHoursPaint = buildTextPaint(R.color.white, lightBold);
            mMinutesPaint = buildTextPaint(R.color.white, lightNormal);
            mDatePaint = buildTextPaint(R.color.white_50, lightNormal);
            mHighTempPaint = buildTextPaint(R.color.white, normal);
            mLowTempPaint = buildTextPaint(R.color.white_50, lightNormal);

            mDividerPaint = buildColoredPaint(R.color.primary);
            mDividerPaint.setStrokeWidth(1f);

            mIconPaint = new Paint();
            mIconPaint.setAntiAlias(true);

            mCalendar = Calendar.getInstance();
            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            } else {
                unregisterReceiver();
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float timeSize = resources.getDimension(R.dimen.text_large);
            float dateSize = resources.getDimension(R.dimen.text_small);
            float temperatuteSize = resources.getDimension(R.dimen.text_normal);

            mHoursPaint.setTextSize(timeSize);
            mMinutesPaint.setTextSize(timeSize);
            mDatePaint.setTextSize(dateSize);
            mHighTempPaint.setTextSize(temperatuteSize);
            mLowTempPaint.setTextSize(temperatuteSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
                    false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mLowBitAmbient) {
                mHoursPaint.setAntiAlias(!inAmbientMode);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            mCalendar.setTimeInMillis(System.currentTimeMillis());

            drawBackground(canvas, bounds);
            drawTime(canvas, bounds);
            drawDate(canvas, bounds);
            drawDivider(canvas, bounds);

            drawForecast(canvas, bounds);
            drawTemp(canvas, bounds);
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
        }

        @NonNull
        private Paint buildTextPaint(@ColorRes int textColor, @NonNull Typeface typeface) {
            Paint paint = buildColoredPaint(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @NonNull
        private Paint buildColoredPaint(@ColorRes int color) {
            Paint paint = new Paint();
            paint.setColor(ContextCompat.getColor(getBaseContext(), color));
            return paint;
        }

        private void drawBackground(@NonNull Canvas canvas, @NonNull Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
        }

        private void drawTime(@NonNull Canvas canvas, @NonNull Rect bounds) {
            int hour = mCalendar.get(Calendar.HOUR);
            int minute = mCalendar.get(Calendar.MINUTE);
            @SuppressLint("DefaultLocale")
            String text = String.format("%d:%02d", hour, minute);

            float width = mHoursPaint.measureText(text);

            canvas.drawText(text, bounds.centerX() - width / 2, mYOffset, mHoursPaint);
        }

        private void drawDate(@NonNull Canvas canvas, @NonNull Rect bounds) {
            String date = mDayOfWeekFormat.format(mCalendar.getTime());
            float width = mDatePaint.measureText(date);
            canvas.drawText(date, bounds.centerX() - width / 2, mDateOffset, mDatePaint);
        }

        private void drawDivider(@NonNull Canvas canvas, @NonNull Rect bounds) {
            float halfDividerWidth = mDividerWidth / 2;
            canvas.drawLine(bounds.centerX() - halfDividerWidth, mDividerYOffset,
                    bounds.centerX() + halfDividerWidth, mDividerYOffset, mDividerPaint);
        }

        private void drawForecast(@NonNull Canvas canvas, @NonNull Rect bounds) {
            if (mWeatherIcon == null) {
                //I leave it here to give ability to watchface without installing mobile app and connecting it
                mWeatherIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);
                mHighTemp = 25;
                mLowTemp = 16;
            }
            int imageHeight = mWeatherIcon.getHeight();
            int imageWidth = mWeatherIcon.getWidth();
            canvas.drawBitmap(mWeatherIcon, bounds.centerX() - 1.5f * imageWidth,
                    mForecastYOffset - (float) (imageHeight / 1.5), mIconPaint);
        }

        private void drawTemp(@NonNull Canvas canvas, @NonNull Rect bounds) {
            String highTemp = String.valueOf(mHighTemp);
            float width = mHighTempPaint.measureText(highTemp);
            canvas.drawText(highTemp, bounds.centerX() - width / 2, mForecastYOffset, mHighTempPaint);

            String lowTemp = String.valueOf(mLowTemp);
            float padding = mLowTempPaint.measureText(" ");
            canvas.drawText(lowTemp, bounds.centerX() + width / 2 + padding, mForecastYOffset, mLowTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
