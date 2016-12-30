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

package com.example.android.sunshinewatchfaceapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWatchFace";
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
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

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHorizontalLine;
        Paint mTextPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mWeatherArtPaint;
        Paint mDatePaint;
        SimpleDateFormat mDayAndDateFormat;
        Calendar mCalendar;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        private static final String WEATHER_INFO_PATH = "/weatherinfo";
        private static final String TEMP_HIGH_KEY = "high_temp";
        private static final String TEMP_LOW_KEY = "low_temp";
        private static final String WEATHER_ID_KEY = "weather_id";

        private double mHighTemp;
        private double mLowTemp;
        private int weatherId;

        Bitmap mWeatherArtBitmap;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        float mLineHeight;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();

            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();
            mCalendar = Calendar.getInstance();

            mHighTempPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mLowTempPaint = new Paint();
            mLowTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDayAndDateFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayAndDateFormat.setCalendar(mCalendar);
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mHorizontalLine = createTextPaint(resources.getColor(R.color.digital_text));

            mWeatherArtPaint = createTextPaint(Color.WHITE);

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
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                mDayAndDateFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
                mDayAndDateFormat.setCalendar(mCalendar);
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, Engine.this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
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
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.date_size_round : R.dimen.date_size);
            float highLowSize = resources.getDimension(isRound
                    ? R.dimen.high_low_size_round : R.dimen.high_low_size);

            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateSize);
            mHighTempPaint.setTextSize(highLowSize);
            mLowTempPaint.setTextSize(highLowSize);
            mWeatherArtPaint.setTextSize(highLowSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

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
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            Date date = new Date();
            date.setTime(System.currentTimeMillis());
            String dateString = mDayAndDateFormat.format(date).toUpperCase();

            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, bounds.exactCenterX()-(mTextPaint.measureText(text)/2), mYOffset, mTextPaint);
            Rect timeBounds = new Rect();
            mTextPaint.getTextBounds(text, 0, text.length(), timeBounds);
            int timeHeight = timeBounds.height();
            canvas.drawText(dateString, bounds.exactCenterX()-(mDatePaint.measureText(dateString)/2), mYOffset+timeHeight, mDatePaint);
            canvas.drawLine(bounds.exactCenterX()-30, bounds.exactCenterY()+20, bounds.exactCenterX()+30,
                    bounds.exactCenterY()+20, mHorizontalLine);
            if (mWeatherArtBitmap != null && !mAmbient) {
                canvas.drawBitmap(mWeatherArtBitmap, mXOffset + 10, bounds.exactCenterY() + 15, mWeatherArtPaint);
                canvas.drawText(formatTemperature(Math.round(mHighTemp)), mXOffset+10+mWeatherArtBitmap.getWidth()+5,
                        bounds.exactCenterY()+80, mHighTempPaint);
                canvas.drawText(formatTemperature(Math.round(mLowTemp)),
                        mXOffset+10+mWeatherArtBitmap.getWidth()+5+mHighTempPaint.measureText(formatTemperature(Math.round(mHighTemp)))+15,
                        bounds.exactCenterY()+80, mLowTempPaint);
            } else {
                canvas.drawText(formatTemperature(Math.round(mHighTemp)), mXOffset+25,
                        bounds.exactCenterY()+80, mHighTempPaint);
                canvas.drawText(formatTemperature(Math.round(mLowTemp)),
                        mXOffset+25+mHighTempPaint.measureText(formatTemperature(Math.round(mHighTemp)))+15,
                        bounds.exactCenterY()+80, mLowTempPaint);
            }


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

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {

            Wearable.DataApi.addListener(mGoogleApiClient,
                    Engine.this);
            Wearable.DataApi.getDataItems(mGoogleApiClient)
                    .setResultCallback(new ResultCallback<DataItemBuffer>() {
                        @Override
                        public void onResult(DataItemBuffer dataItems) {
                            Status status = dataItems.getStatus();

                            if (status.isSuccess()) {

                                for (DataItem dataItem : dataItems) {

                                    Log.i(TAG, "Synced weather Info " + dataItem);
                                    if (dataItem != null) {

                                            DataMap dataMap =
                                                    DataMapItem.fromDataItem(dataItem).getDataMap();

                                            if (dataMap.containsKey(TEMP_HIGH_KEY) || dataMap.containsKey(TEMP_LOW_KEY)
                                                    || dataMap.containsKey(WEATHER_ID_KEY)) {
                                                weatherId = dataMap.getInt(WEATHER_ID_KEY);
                                                mWeatherArtBitmap = ((BitmapDrawable)getResources().getDrawable(getArtResourceForWeatherCondition(weatherId))).getBitmap();
                                                mHighTemp = dataMap.getDouble(TEMP_HIGH_KEY);
                                                mLowTemp = dataMap.getDouble(TEMP_LOW_KEY);
                                                Log.i(TAG, "(OnResult) High & Low temperatures are: " + mHighTemp + "&" + mLowTemp);
                                            }else {
                                                Log.i(TAG, "Failed to receive data from phone!");
                                            }
                                            invalidate();
                                    }
                                }
                            } else {
                                Log.i(TAG, "Failed getting weather info: (" + status.getStatusCode() + ") " + status.getStatusMessage());
                            }

                            dataItems.release();
                        }
                    });
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }

        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if ((item.getUri().getPath()).
                            equals(WEATHER_INFO_PATH)) {
                        DataMap dataMap =
                                DataMapItem.fromDataItem(item).getDataMap();

                        if (dataMap.containsKey(TEMP_HIGH_KEY) || dataMap.containsKey(TEMP_LOW_KEY)
                                || dataMap.containsKey(WEATHER_ID_KEY)) {
                            weatherId = dataMap.getInt(WEATHER_ID_KEY);
                            mWeatherArtBitmap = ((BitmapDrawable)getResources().getDrawable(getArtResourceForWeatherCondition(weatherId))).getBitmap();
                            mHighTemp = dataMap.getDouble(TEMP_HIGH_KEY);
                            mLowTemp = dataMap.getDouble(TEMP_LOW_KEY);
                            Log.i(TAG, "(OnDataCHanged) High & Low temperatures are: " + mHighTemp + "&" + mLowTemp);
                        }else {
                            Log.i(TAG, "Failed to receive data from phone!");
                        }
                        invalidate();
                    }
                }
            }
        }

        public int getArtResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }

        public String formatTemperature(double temperature) {

            // For presentation, assume the user doesn't care about tenths of a degree.
            return String.format(getString(R.string.format_temperature), temperature);
        }
    }
}
