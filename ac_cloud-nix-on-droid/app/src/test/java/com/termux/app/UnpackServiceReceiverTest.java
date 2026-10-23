package com.termux.app;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import com.termux.cloud.UnpackReport;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #1121 A bad or missing reply channel must never stop the unpack: the phone crashed in
 * getParcelableExtra (BadParcelableException) before any file was unpacked. Same file in both terminals.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UnpackServiceReceiverTest {

    @Test
    public void noIntentOrNoExtraMeansNoReply() {
        Assert.assertNull(UnpackService.receiverOf(null));
        Assert.assertNull(UnpackService.receiverOf(new Intent(UnpackReport.ACTION)));
    }

    @Test
    public void anExtraOfTheWrongTypeMeansNoReplyAndNoCrash() {
        Intent intent = new Intent(UnpackReport.ACTION).putExtra(UnpackReport.EXTRA_RECEIVER, "not a receiver");
        Assert.assertNull(UnpackService.receiverOf(intent));
    }

    @Test
    public void aReceiverIsReturned() {
        ResultReceiver r = new ResultReceiver(new Handler(Looper.getMainLooper()));
        Intent intent = new Intent(UnpackReport.ACTION).putExtra(UnpackReport.EXTRA_RECEIVER, r);
        Assert.assertSame(r, UnpackService.receiverOf(intent));
    }
}
