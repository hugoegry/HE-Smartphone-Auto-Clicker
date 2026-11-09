package com.he.he_smartphone_auto_clicker.vision

import org.junit.Assert.assertTrue
import org.junit.Test

class TextQueryTest {
    private val q = TextQuery.SAMPLE

    @Test fun bannerPourboire() {
        val s = TextQuery.best("LAISSEZ UN POURBOIRE POUR UNE VIRÉE SANS NUAGE", q)
        assertTrue("$s", s >= 0.9f)
    }

    @Test fun pourBoireCoupe() {
        val s = TextQuery.best("laissez un pour boire pour une viree sans nuage", q)
        assertTrue("$s", s >= 0.9f)
    }

    @Test fun barreDirigee() {
        val s = TextQuery.best("LA BARRE EST DIRIGÉE PAR Guts1814", q)
        assertTrue("$s", s >= 0.9f)
    }

    @Test fun barreSansLePar() {
        val s = TextQuery.best("LA BARRE EST DIRIGEE PR:", TextQuery.BAR)
        assertTrue("$s", s >= 0.99f)
    }

    @Test fun uneLettreFoiree() {
        val s = TextQuery.best("la barre est diriqee par bob", q)
        assertTrue("$s", s >= 0.75f)
    }

    @Test fun moitieDePhrase() {
        val s = TextQuery.best("laissez un pourboire pour une", q)
        assertTrue("$s", s >= 0.7f)
    }

    @Test fun autreTexteDuJeu() {
        val s = TextQuery.best("VISER POUR SAUTER", q)
        assertTrue("$s", s < 0.5f)
    }

    @Test fun motSeul() {
        val s = TextQuery.best("pourboire", q)
        assertTrue("$s", s < 0.5f)
    }

    @Test fun colleSansEspace() {
        val s = TextQuery.best("laissezunpourboirepourunevireesansnuage", q)
        assertTrue("$s", s >= 0.9f)
    }
}
