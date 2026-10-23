package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeInferenceTest {
    private fun kmh(v: Double) = v / 3.6
    @Test fun `speed bands without steps`() {
        assertEquals(Mode.WALK, ModeInference.infer(kmh(4.0), 0.0))
        assertEquals(Mode.BIKE, ModeInference.infer(kmh(15.0), 0.0))
        assertEquals(Mode.CAR, ModeInference.infer(kmh(60.0), 0.0))
        assertEquals(Mode.TRAIN, ModeInference.infer(kmh(180.0), 0.0))
        assertEquals(Mode.PLANE, ModeInference.infer(kmh(700.0), 0.0))
    }
    @Test fun `band edges`() {
        assertEquals(Mode.WALK, ModeInference.infer(ModeInference.WALK_MAX_MPS, 0.0))
        assertEquals(Mode.BIKE, ModeInference.infer(ModeInference.WALK_MAX_MPS + 0.01, 0.0))
        assertEquals(Mode.BIKE, ModeInference.infer(ModeInference.BIKE_MAX_MPS, 0.0))
        assertEquals(Mode.CAR, ModeInference.infer(ModeInference.BIKE_MAX_MPS + 0.01, 0.0))
        assertEquals(Mode.CAR, ModeInference.infer(ModeInference.CAR_MAX_MPS, 0.0))
        assertEquals(Mode.TRAIN, ModeInference.infer(ModeInference.CAR_MAX_MPS + 0.01, 0.0))
        assertEquals(Mode.TRAIN, ModeInference.infer(ModeInference.TRAIN_MAX_MPS, 0.0))
        assertEquals(Mode.PLANE, ModeInference.infer(ModeInference.TRAIN_MAX_MPS + 0.01, 0.0))
    }
    @Test fun `step cadence separates walking and running from cycling at the same speed`() {
        assertEquals(Mode.RUN, ModeInference.infer(kmh(10.0), 160.0))
        assertEquals(Mode.WALK, ModeInference.infer(kmh(9.0), 100.0))
        assertEquals(Mode.BIKE, ModeInference.infer(kmh(14.0), 0.0))
    }
    @Test fun `a vehicle with a phone in a pocket is still a vehicle`() {
        assertEquals(Mode.CAR, ModeInference.infer(kmh(50.0), 5.0))
    }
}
