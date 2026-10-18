package com.sebas.traductorapp.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputTextTest {

    @Test
    fun literalBackslashNBecomesRealLineBreak() {
        assertEquals(
            "Nombre: Devoraespíritus\nRango: Único\nNivel: 0/100",
            OutputText.clean("Nombre: Devoraespíritus\\nRango: Único\\nNivel: 0/100"),
        )
    }

    @Test
    fun realBreaksAndSpacesAreTidied() {
        assertEquals("¿Qué?\n¡No puede ser!", OutputText.clean("  ¿Qué?  \r\n\n\n ¡No   puede ser!  "))
    }

    @Test
    fun literalCrLfAndTabs() {
        assertEquals("A\nB C", OutputText.clean("A\\r\\nB\\tC"))
    }

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("Se ha recolectado sangre de unicornio.", OutputText.clean("Se ha recolectado sangre de unicornio."))
    }
}
