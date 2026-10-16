package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.decide.Attachment
import com.diegonmarcos.cloudcalc.decide.Attachments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model is sent when things are attached to a Jev request. */
class AttachmentsTest {
    @Test fun `no attachment is the plain request object`() {
        val s = Attachments.state("convert 3 ft", emptyList()) as JSONObject
        assertEquals("convert 3 ft", s.getString("request"))
    }

    @Test fun `an image becomes an image_url part, a text file is quoted in the text, audio an input_audio part`() {
        val atts = listOf(
            Attachment("a.jpg", Attachment.Kind.IMAGE, "image/jpeg", "QUJD", 3),
            Attachment("n.txt", Attachment.Kind.TEXT, "text/plain", "hello", 5),
            Attachment("v.wav", Attachment.Kind.AUDIO, "audio/wav", "UklGRg==", 4),
            Attachment("x.bin", Attachment.Kind.FILE, "application/octet-stream", "", 9),
        )
        val parts = Attachments.state("what is this", atts) as JSONArray
        assertEquals(3, parts.length())
        val text = parts.getJSONObject(0)
        assertEquals("text", text.getString("type"))
        assertTrue(text.getString("text").startsWith("what is this"))
        assertTrue(text.getString("text").contains("[attached file n.txt]\nhello"))
        assertTrue(text.getString("text").contains("x.bin"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val audio = parts.getJSONObject(2)
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("wav", audio.getJSONObject("input_audio").getString("format"))
        assertEquals("UklGRg==", audio.getJSONObject("input_audio").getString("data"))
    }
}
