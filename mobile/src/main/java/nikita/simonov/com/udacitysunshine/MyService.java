package nikita.simonov.com.udacitysunshine;

import android.text.TextUtils;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

public class MyService extends WearableListenerService {

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent dataEvent : dataEventBuffer) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                DataMap dataMap = DataMapItem
                        .fromDataItem(dataEvent.getDataItem())
                        .getDataMap();
                String path = dataEvent.getDataItem().getUri().getPath();
                if (TextUtils.equals(path, "/step-counter")) {
                    int steps = dataMap.getInt("step-count");
                    long timestamp = dataMap.getLong("timestamp");
                }
            }
        }
    }
}
