package io.github.leohelfferich.abstain.utils

import java.util.Locale

/** Decimal points stay dots; the default locale would print EUR 10,00 and break the committed checks. */
fun String.formatUs(vararg args: Any?): String = java.lang.String.format(Locale.US, this, *args)
