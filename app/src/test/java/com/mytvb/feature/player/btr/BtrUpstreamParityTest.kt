package com.mytvb.feature.player.btr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BtrUpstreamParityTest {
    private val fixtures = JsonParser.parseReader(javaClass.getResourceAsStream("/btr-parity.json")!!.reader()).asJsonObject
    private fun JsonObject.d(key: String) = get(key).asDouble
    private fun JsonObject.i(key: String) = get(key).asInt
    private fun JsonObject.l(key: String) = get(key).asLong
    @Test fun smoothWeightsExplorationAndRotationMatchUpstream() {
        for (ranges in fixtures.getAsJsonArray("assignments")) {
            val policy = BtrSchedulingPolicy(); val state = BtrSchedulingPolicy.TrialState()
            for (range in ranges.asJsonArray) {
                val r = range.asJsonObject; val speeds = r.getAsJsonArray("speeds").map { it.asDouble }
                assertEquals(r.getAsJsonArray("expected").map { it.asInt }, policy.assign(speeds.indices.toList(), r.i("count"), state) { speeds[it] })
            }
        }
    }
    @Test fun adaptiveChunkSizesAndRangeBoundariesMatchUpstream() {
        for (item in fixtures.getAsJsonArray("sizing")) {
            val r = item.asJsonObject; val policy = BtrSchedulingPolicy()
            policy.record(r.i("bytes"), r.d("elapsed"))
            val minimum = policy.minChunk(r.l("length"), r.i("limit"), r.i("hosts"))
            assertEquals(r.l("minimum"), minimum)
            assertEquals(r.d("delay"), policy.hedgeDelay(), .00001)
            val expected = r.getAsJsonArray("pieces").map { it.asJsonObject }.map { BtrSchedulingPolicy.Piece(it.i("index"),it.l("start"),it.l("end")) }
            assertEquals(expected, BtrSchedulingPolicy.split(71, 70+r.l("length"), r.i("limit"), minimum))
        }
    }
    @Test fun playbackDeadlineAndPaceDecisionsMatchUpstream() {
        for (item in fixtures.getAsJsonArray("hedges")) {
            val r = item.asJsonObject; val policy = BtrSchedulingPolicy()
            policy.record(r.i("meterBytes"),r.d("meterMs"))
            val p = BtrSchedulingPolicy.Progress(r.d("started"),r.i("bytes"),r.i("length"),r.d("lastProgress"))
            assertEquals(r.get("expected").asBoolean, policy.hedgeDue(p,r.d("now"),r.d("delay"),r.d("deadline"),r.d("copyBps"), BtrSchedulingPolicy.Rescue(1)))
        }
    }
    @Test fun automaticThreadsMatchUpstreamEventTraces() {
        for ((scenario, events) in fixtures.getAsJsonArray("auto").withIndex()) {
            var at=0L; val controller=BtrAutoController { at }
            for ((step,event) in events.asJsonArray.withIndex()) {
                val r=event.asJsonObject; at=r.l("at"); val args=r.getAsJsonArray("args")
                when(r.get("type").asString) {
                    "newSession" -> controller.newSession()
                    "delivered" -> controller.delivered(args[0].asLong)
                    "activity" -> controller.activity()
                    "demand" -> controller.demand(args[0].asInt,args[1].asInt,args[2].asInt)
                    "buffer" -> controller.buffer(args[0].asDouble,args[1].asBoolean)
                    "stall" -> controller.stall()
                    "slow" -> controller.slow()
                    "pushback" -> controller.pushback(args[0].asInt)
                }
                val expected=r.getAsJsonObject("expected"); val actual=controller.status(); val label="scenario $scenario step $step"
                assertEquals(label,expected.i("threads"),actual.threads)
                assertEquals(label,expected.i("level"),actual.level)
                assertEquals(label,expected.d("throughputBps"),actual.throughputBps.toDouble(),1.0)
                assertEquals(label,expected.d("saturation"),actual.saturation,.0051)
                val t=expected.get("trial")
                if (t.isJsonNull) assertNull(label,actual.trial) else {
                    assertNotNull(label,actual.trial)
                    assertEquals(label,t.asJsonObject.i("from"),actual.trial!!.from)
                    assertEquals(label,t.asJsonObject.i("to"),actual.trial!!.to)
                    assertEquals(label,t.asJsonObject.l("ageMs"),actual.trial!!.ageMs)
                }
            }
        }
    }
}
