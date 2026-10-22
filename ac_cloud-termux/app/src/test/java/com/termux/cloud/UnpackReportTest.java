package com.termux.cloud;

import org.junit.Assert;
import org.junit.Test;

/** #1121 The self-unpack's vocabulary: what runs it, and what it writes. Same file in both terminals. */
public class UnpackReportTest {

    @Test
    public void onlyTheUpdateBroadcastAndTheFleetStartRunAnUnpack() {
        Assert.assertTrue(UnpackReport.runsOn("android.intent.action.MY_PACKAGE_REPLACED"));
        Assert.assertTrue(UnpackReport.runsOn("com.diegonmarcos.cloud.action.UNPACK"));
        Assert.assertFalse(UnpackReport.runsOn("android.intent.action.BOOT_COMPLETED"));
        Assert.assertFalse(UnpackReport.runsOn("android.intent.action.PACKAGE_REPLACED"));
        Assert.assertFalse(UnpackReport.runsOn(null));
    }

    @Test
    public void aSuccessIsOkAndNamesThePackage() {
        String j = UnpackReport.json("cld.termux", null, 42L);
        Assert.assertEquals("{\"ok\":true,\"package\":\"cld.termux\",\"at\":42}", j);
        Assert.assertTrue(UnpackReport.isOk(j));
    }

    @Test
    public void aFailureCarriesItsReasonEscaped() {
        String j = UnpackReport.json("cld.termux.nix", "bad \"lib\"\nline2", 7L);
        Assert.assertEquals("{\"ok\":false,\"package\":\"cld.termux.nix\",\"at\":7,\"error\":\"bad \\\"lib\\\"\\nline2\"}", j);
        Assert.assertFalse(UnpackReport.isOk(j));
    }

    @Test
    public void nothingIsOkWhenNothingWasWritten() {
        Assert.assertFalse(UnpackReport.isOk(null));
        Assert.assertFalse(UnpackReport.isOk(""));
    }

    @Test
    public void theActionIsTheOneTheStoreAndTheRegistryName() {
        Assert.assertEquals("com.diegonmarcos.cloud.action.UNPACK", UnpackReport.ACTION);
    }
}
