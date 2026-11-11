package com.chami.pdfmaker

import com.chami.pdfmaker.pdf.PdfProtector
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExampleUnitTest {
  @Test
  fun testPasswordEncryptionAndVerification() {
    val tempFile = File.createTempFile("test_encrypt", ".pdf")
    try {
      val docId = "1234567890abcdef1234567890abcdef".toByteArray()
      val enc = PdfProtector.computeEncryptionParams("MySecret123", "", docId)

      assertNotNull(enc.encryptionKey)
      assertEquals(5, enc.encryptionKey.size)
      assertEquals(64, enc.oHex.length)
      assertEquals(64, enc.uHex.length)
    } finally {
      tempFile.delete()
    }
  }
}
