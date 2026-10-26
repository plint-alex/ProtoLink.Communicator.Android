package ru.protolink.communicator.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import java.io.InputStream;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBitmapFactory;

/**
 * Robolectric's legacy decoder returns a Bitmap for inJustDecodeBounds decodes; real Android
 * returns null. Enforce the device contract so callers relying on the return value fail in tests.
 */
@Implements(value = BitmapFactory.class, isInAndroidSdk = false)
public class ShadowStrictBoundsBitmapFactory extends ShadowBitmapFactory {
    @Implementation
    protected static Bitmap decodeStream(InputStream is, Rect outPadding, BitmapFactory.Options opts) {
        Bitmap bitmap = ShadowBitmapFactory.decodeStream(is, outPadding, opts);
        return opts != null && opts.inJustDecodeBounds ? null : bitmap;
    }
}
