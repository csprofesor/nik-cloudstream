package com.nikyokki

/**
 * Null-safe helpers used by DiziGom's poster candidate parser.
 * The parser intentionally collects optional HTML attributes, so the sequence
 * can contain nullable strings before the final URL cleanup stage.
 */
private fun String?.isNotBlank(): Boolean = !this.isNullOrBlank()

private fun String?.split(delimiter: String): List<String> = this?.split(delimiter).orEmpty()
