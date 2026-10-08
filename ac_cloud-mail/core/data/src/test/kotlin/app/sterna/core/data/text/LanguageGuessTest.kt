package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {
    @Test fun `common languages are recognised`() {
        assertEquals("en", LanguageGuess.guess("Thank you for your order. We will send you an email when it has shipped and you can track it from your account."))
        assertEquals("es", LanguageGuess.guess("Gracias por su pedido. Le enviaremos un correo cuando haya sido enviado y podrá seguirlo desde su cuenta."))
        assertEquals("fr", LanguageGuess.guess("Merci pour votre commande. Nous vous enverrons un courriel lorsque celle-ci sera expédiée et vous pourrez la suivre."))
        assertEquals("de", LanguageGuess.guess("Vielen Dank für Ihre Bestellung. Wir senden Ihnen eine E-Mail, sobald sie versandt wurde, und Sie können sie verfolgen."))
        assertEquals("ru", LanguageGuess.guess("Спасибо за ваш заказ. Мы отправим вам письмо, когда он будет отправлен."))
        assertEquals("ja", LanguageGuess.guess("ご注文ありがとうございます。発送が完了しましたらメールでお知らせします。"))
        assertEquals("zh", LanguageGuess.guess("感谢您的订购。发货后我们会通过电子邮件通知您。"))
    }

    @Test fun `an unsure text is not guessed`() {
        assertNull(LanguageGuess.guess(""))
        assertNull(LanguageGuess.guess("12:30 | 5,00 | #4821"))
        assertNull("too few words", LanguageGuess.guess("ok thanks"))
        assertNull("names and product codes", LanguageGuess.guess("Zorblax Quendrix Mavrolent Pyxtra Nofrelith"))
    }
}
