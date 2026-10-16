package org.fossify.phone.sms

import org.fossify.phone.sms.mms.MmsCodec
import org.fossify.phone.sms.mms.MmsPart
import org.fossify.phone.sms.mms.MmsPdu
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsCodecTest {
    @Test
    fun aSendRequestRoundTripsItsRecipientsAndParts() {
        val jpeg = ByteArray(300) { (it * 7).toByte() }
        val bytes = MmsCodec.encodeSendReq(
            "tx-42", listOf("+15551234567", "+4930123456"), null,
            listOf(MmsPart("text/plain", "text.txt", "hello wörld".toByteArray()), MmsPart("image/jpeg", "image.jpg", jpeg)),
        )
        val pdu = MmsCodec.decode(bytes)
        assertNotNull(pdu)
        pdu!!
        assertEquals(MmsPdu.M_SEND_REQ, pdu.messageType)
        assertEquals("tx-42", pdu.transactionId)
        assertEquals(listOf("+15551234567", "+4930123456"), pdu.to)
        assertEquals(2, pdu.parts.size)
        assertEquals("text/plain", pdu.parts[0].contentType)
        assertEquals("text.txt", pdu.parts[0].name)
        assertEquals("hello wörld", pdu.text)
        assertEquals("image/jpeg", pdu.parts[1].contentType)
        assertEquals("image.jpg", pdu.parts[1].name)
        assertArrayEquals(jpeg, pdu.parts[1].data)
    }

    @Test
    fun aNotificationNamesWhereTheMessageWaits() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x8C.toByte(), 0x82.toByte()))                             // m-notification-ind
        out.write(byteArrayOf(0x98.toByte())); out.write("T-1".toByteArray()); out.write(0)  // transaction id
        out.write(byteArrayOf(0x8D.toByte(), 0x92.toByte()))                             // version 1.2
        val from = "+15550001/TYPE=PLMN".toByteArray()
        out.write(byteArrayOf(0x89.toByte(), (from.size + 2).toByte(), 0x80.toByte())); out.write(from); out.write(0)
        out.write(byteArrayOf(0x8A.toByte(), 0x80.toByte()))                             // class: personal
        out.write(byteArrayOf(0x8E.toByte(), 0x02, 0x04, 0x00))                          // size 1024
        out.write(byteArrayOf(0x88.toByte(), 0x05, 0x81.toByte(), 0x03, 0x02, 0x00, 0x00)) // expiry (skipped)
        out.write(byteArrayOf(0x83.toByte())); out.write("http://mms.example/abc".toByteArray()); out.write(0)
        val pdu = MmsCodec.decode(out.toByteArray())!!
        assertTrue(pdu.isNotification)
        assertEquals("T-1", pdu.transactionId)
        assertEquals("+15550001", pdu.from)
        assertEquals(1024L, pdu.size)
        assertEquals("http://mms.example/abc", pdu.contentLocation)
        assertTrue(pdu.parts.isEmpty())
    }

    @Test
    fun garbageIsNotAPdu() {
        assertNull(MmsCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(MmsCodec.decode(ByteArray(0)))
    }

    @Test
    fun aTruncatedPduIsRejectedNotCrashed() {
        val bytes = MmsCodec.encodeSendReq("t", listOf("+1555"), null, listOf(MmsPart("image/jpeg", "i.jpg", ByteArray(100))))
        val cut = bytes.copyOf(bytes.size - 50)
        val pdu = MmsCodec.decode(cut)
        assertTrue(pdu == null || pdu.parts.isEmpty() || pdu.parts[0].data.size <= 100)
    }
}
