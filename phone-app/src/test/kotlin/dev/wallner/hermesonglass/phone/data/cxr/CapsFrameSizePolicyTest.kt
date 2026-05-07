package dev.wallner.hermesonglass.phone.data.cxr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsFrameSizePolicyTest {

    @Test fun `tiny payload sends`() {
        val decision = CapsFrameSizePolicy.decide(ByteArray(64))
        assertEquals(CapsFrameSizePolicy.Decision.Send, decision)
    }

    @Test fun `payload at warn threshold sends`() {
        val decision = CapsFrameSizePolicy.decide(ByteArray(CapsFrameSizePolicy.WARN_BYTES))
        assertEquals(CapsFrameSizePolicy.Decision.Send, decision)
    }

    @Test fun `payload above warn but below hard reject still sends`() {
        val decision = CapsFrameSizePolicy.decide(ByteArray(CapsFrameSizePolicy.WARN_BYTES + 1))
        assertEquals(CapsFrameSizePolicy.Decision.Send, decision)
    }

    @Test fun `payload over hard reject is rejected with a useful reason`() {
        val decision = CapsFrameSizePolicy.decide(
            ByteArray(CapsFrameSizePolicy.HARD_REJECT_BYTES + 1),
        )
        val r = decision as CapsFrameSizePolicy.Decision.Reject
        assertTrue(r.reason.contains("sendStream"))
    }
}
