package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalDictionaryTest {
    private fun lat(text: String) = LocalDictionary.translate(text, SpanishVariant.LATINO)
    private fun esp(text: String) = LocalDictionary.translate(text, SpanishVariant.ESPANA)

    @Test
    fun examplesFromSpec() {
        assertEquals("¡¿Qué?!", lat("WHAT?!"))
        assertEquals("¿Eh?", lat("Huh?"))
        assertEquals("Jajaja", lat("HAHAHA"))
        assertEquals("Gracias.", lat("Thanks."))
        assertNull(lat("I see."))
    }

    @Test
    fun openingMarksFollowTrailingPunctuation() {
        assertEquals("¡¿Eh?!", lat("huh?!"))
        assertEquals("¡¿Qué?!", lat("What!?"))
        assertEquals("¡Oye!", lat("HEY!"))
        assertEquals("¿En serio?", lat("REALLY?"))
        assertEquals("¿Por qué?", lat("Why?"))
        assertEquals("Mmm...", lat("Hmmm..."))
        assertEquals("¿Qué...?", lat("What...?"))
        assertEquals("...¿Eh?", lat("...Huh?"))
    }

    @Test
    fun repeatedLettersAreCollapsed() {
        assertEquals("¿Qué?", lat("WHAAAT?"))
        assertEquals("Mmm", lat("HMMMM"))
        assertEquals("Ughhh", lat("UGHHH"))
        assertEquals("¡Nooo!", lat("NOOO!"))
        assertEquals("Aah", lat("Aah"))
        assertEquals("Perdón", lat("Sorryyy"))
    }

    @Test
    fun laughsScaleWithRepeats() {
        assertEquals("Ja", lat("HA"))
        assertEquals("¡Jaja!", lat("HAHA!"))
        assertEquals("Jejeje", lat("hehehe"))
        assertEquals("Ja, ja", lat("HA HA"))
        assertEquals("¡Muajaja!", lat("MUAHAHA!"))
    }

    @Test
    fun okayDependsOnVariant() {
        assertEquals("Está bien.", lat("OKAY."))
        assertEquals("Vale.", esp("OKAY."))
        assertEquals("¡Vale!", esp("Ok!"))
    }

    @Test
    fun twoWordPhrasesAndRepeats() {
        assertEquals("¡Oh, no!", lat("OH, NO!"))
        assertEquals("Gracias.", lat("Thank you."))
        assertEquals("¡No, no!", lat("No, no!"))
        assertEquals("¡No puede ser!", lat("NO WAY!"))
    }

    @Test
    fun nonInterjectionsReturnNull() {
        assertNull(lat("He?"))
        assertNull(lat("WHAT IS THAT?"))
        assertNull(lat("Run!"))
        assertNull(lat("Oh, really?"))
        assertNull(lat("LEVEL 12"))
        assertNull(lat(""))
        assertNull(lat("?!"))
    }
}
